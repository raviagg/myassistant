package com.myassistant.db.repositories

import com.myassistant.domain.{CreateScheduledJob, ScheduledJob, ScheduledJobRun, UpdateScheduledJob}
import com.myassistant.errors.AppError
import io.circe.JsonObject
import io.circe.parser as circeParser
import io.circe.syntax.*
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Data-access interface for `scheduled_job` and `scheduled_job_run` tables. */
trait ScheduledJobRepository:

  /** Insert a new scheduled job and return the persisted record. */
  def create(req: CreateScheduledJob): ZIO[ZConnectionPool, AppError, ScheduledJob]

  /** Fetch a job by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]]

  /** List all jobs belonging to a specific person. */
  def findByPersonId(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]]

  /** List all jobs belonging to a specific household. */
  def findByHouseholdId(householdId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]]

  /** List enabled jobs whose next_run_at is NULL or in the past — ready to execute. */
  def findDueJobs(): ZIO[ZConnectionPool, AppError, List[ScheduledJob]]

  /** Advance the next_run_at timestamp after a successful execution. */
  def updateNextRunAt(id: UUID, nextRunAt: Instant): ZIO[ZConnectionPool, AppError, Unit]

  /** Apply a partial update (cron, config, enabled) to an existing job. */
  def update(id: UUID, req: UpdateScheduledJob): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]]

  /** Delete a job; returns true if a row was removed. */
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

  /** Insert a run record and return the persisted record. */
  def createRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRun]

  /** List all runs for a given job in descending started_at order. */
  def findRunsByJobId(jobId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobRun]]

object ScheduledJobRepository:

  // ── Row types ─────────────────────────────────────────────────────────────
  // id, source_type, person_id, household_id, cron_expression, config,
  // enabled, next_run_at, created_at
  private type JobRow =
    (String, String, Option[String], Option[String], String, String,
     Boolean, Option[java.sql.Timestamp], java.sql.Timestamp)

  // id, job_id, started_at, finished_at, status, status_detail
  private type RunRow =
    (String, String, java.sql.Timestamp, Option[java.sql.Timestamp],
     String, Option[String])

  // ── Shared column lists ───────────────────────────────────────────────────
  private val jobCols = SqlFragment(
    """id::text, source_type, person_id::text, household_id::text,
       cron_expression, config::text, enabled, next_run_at, created_at"""
  )

  private val runCols = SqlFragment(
    """id::text, job_id::text, started_at, finished_at, status, status_detail"""
  )

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToJob(row: JobRow): ScheduledJob =
    val (id, sourceType, personId, householdId, cronExpression, configStr, enabled, nextRunAt, createdAt) = row
    val config = circeParser.parse(configStr)
      .toOption
      .flatMap(_.asObject)
      .getOrElse(JsonObject.empty)
    ScheduledJob(
      id             = UUID.fromString(id),
      sourceType     = sourceType,
      personId       = personId.map(UUID.fromString),
      householdId    = householdId.map(UUID.fromString),
      cronExpression = cronExpression,
      config         = config,
      enabled        = enabled,
      nextRunAt      = nextRunAt.map(_.toInstant),
      createdAt      = createdAt.toInstant,
    )

  private def rowToRun(row: RunRow): ScheduledJobRun =
    val (id, jobId, startedAt, finishedAt, status, statusDetail) = row
    ScheduledJobRun(
      id           = UUID.fromString(id),
      jobId        = UUID.fromString(jobId),
      startedAt    = startedAt.toInstant,
      finishedAt   = finishedAt.map(_.toInstant),
      status       = status,
      statusDetail = statusDetail,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends ScheduledJobRepository:

    def create(req: CreateScheduledJob): ZIO[ZConnectionPool, AppError, ScheduledJob] =
      val id        = UUID.randomUUID()
      val configStr = req.config.asJson.noSpaces
      val q =
        sql"INSERT INTO scheduled_job(id, source_type, person_id, household_id, cron_expression, config, enabled) " ++
        sql"VALUES (${id.toString}::uuid, ${req.sourceType}, ${req.personId.map(_.toString)}::uuid, " ++
        sql"${req.householdId.map(_.toString)}::uuid, ${req.cronExpression}, ${configStr}::jsonb, ${req.enabled}) " ++
        sql"RETURNING " ++ jobCols
      transaction(q.query[JobRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT scheduled_job returned no row"))))
        .map(rowToJob)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]] =
      val q = sql"SELECT " ++ jobCols ++
              sql" FROM scheduled_job WHERE id = ${id.toString}::uuid"
      transaction(q.query[JobRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToJob))

    def findByPersonId(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]] =
      val q = sql"SELECT " ++ jobCols ++
              sql" FROM scheduled_job WHERE person_id = ${personId.toString}::uuid ORDER BY created_at DESC"
      transaction(q.query[JobRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToJob))

    def findByHouseholdId(householdId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJob]] =
      val q = sql"SELECT " ++ jobCols ++
              sql" FROM scheduled_job WHERE household_id = ${householdId.toString}::uuid ORDER BY created_at DESC"
      transaction(q.query[JobRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToJob))

    def findDueJobs(): ZIO[ZConnectionPool, AppError, List[ScheduledJob]] =
      val q = sql"SELECT " ++ jobCols ++
              sql" FROM scheduled_job WHERE enabled = true AND (next_run_at IS NULL OR next_run_at <= NOW()) ORDER BY next_run_at ASC NULLS FIRST"
      transaction(q.query[JobRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToJob))

    def updateNextRunAt(id: UUID, nextRunAt: Instant): ZIO[ZConnectionPool, AppError, Unit] =
      val ts = java.sql.Timestamp.from(nextRunAt)
      val q  = sql"UPDATE scheduled_job SET next_run_at = $ts WHERE id = ${id.toString}::uuid"
      transaction(q.update)
        .mapError(mapSqlError)
        .unit

    def update(id: UUID, req: UpdateScheduledJob): ZIO[ZConnectionPool, AppError, Option[ScheduledJob]] =
      val assignments: List[SqlFragment] = List.concat(
        req.cronExpression.map(v =>
          sql"cron_expression = $v"
        ),
        req.config.map(c =>
          sql"config = ${c.asJson.noSpaces}::jsonb"
        ),
        req.enabled.map(e =>
          sql"enabled = $e"
        ),
        req.nextRunAt.map {
          case None     => sql"next_run_at = NULL"
          case Some(ts) => sql"next_run_at = ${java.sql.Timestamp.from(ts)}"
        },
      )
      if assignments.isEmpty then
        findById(id)
      else
        val setFrag = assignments.reduce(_ ++ SqlFragment(", ") ++ _)
        val q = sql"UPDATE scheduled_job SET " ++ setFrag ++
                sql" WHERE id = ${id.toString}::uuid RETURNING " ++ jobCols
        transaction(q.query[JobRow].selectOne)
          .mapError(mapSqlError)
          .map(_.map(rowToJob))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction(
        sql"DELETE FROM scheduled_job WHERE id = ${id.toString}::uuid".delete
      ).mapError(mapSqlError)
        .map(_ > 0)

    def createRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRun] =
      val startedTs  = java.sql.Timestamp.from(run.startedAt)
      val finishedTs = run.finishedAt.map(java.sql.Timestamp.from)
      val q =
        sql"INSERT INTO scheduled_job_run(id, job_id, started_at, finished_at, status, status_detail) " ++
        sql"VALUES (${run.id.toString}::uuid, ${run.jobId.toString}::uuid, $startedTs, $finishedTs, " ++
        sql"${run.status}, ${run.statusDetail}) " ++
        sql"RETURNING " ++ runCols
      transaction(q.query[RunRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT scheduled_job_run returned no row"))))
        .map(rowToRun)

    def findRunsByJobId(jobId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobRun]] =
      val q = sql"SELECT " ++ runCols ++
              sql" FROM scheduled_job_run WHERE job_id = ${jobId.toString}::uuid ORDER BY started_at DESC"
      transaction(q.query[RunRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToRun))

  /** ZLayer providing the live ScheduledJobRepository. */
  val live: ZLayer[Any, Nothing, ScheduledJobRepository] =
    ZLayer.succeed(new Live)
