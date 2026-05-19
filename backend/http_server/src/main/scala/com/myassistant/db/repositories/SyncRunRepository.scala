package com.myassistant.db.repositories

import com.myassistant.api.models.PatchSyncRunRequest
import com.myassistant.domain.{LatestSyncRuns, SyncRun}
import com.myassistant.errors.AppError
import io.circe.{Json, JsonObject}
import io.circe.parser as circeParser
import io.circe.syntax.*
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Data-access interface for the `sync_runs` table. */
trait SyncRunRepository:

  /** Insert a new run record and return the persisted record. */
  def create(run: SyncRun): ZIO[ZConnectionPool, AppError, SyncRun]

  /** List recent runs for a connection, newest first. */
  def findByConnectionId(
      sourceConnectionId: UUID,
      limit:              Int,
  ): ZIO[ZConnectionPool, AppError, List[SyncRun]]

  /** Fetch a single run by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[SyncRun]]

  /** Find the latest adhoc run and the latest scheduled run for a connection. */
  def findLatest(sourceConnectionId: UUID): ZIO[ZConnectionPool, AppError, LatestSyncRuns]

  /** Apply a partial update to a sync_runs row.
   *  Returns None if the row does not exist or the connection_id mismatch.
   */
  def patch(
      runId:              UUID,
      sourceConnectionId: UUID,
      req:                PatchSyncRunRequest,
  ): ZIO[ZConnectionPool, AppError, Option[SyncRun]]

object SyncRunRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, source_connection_id, run_type, status,
  // started_at, completed_at, stats, log_lines
  private type RunRow =
    (String, String, String, String,
     java.sql.Timestamp, Option[java.sql.Timestamp],
     Option[String], String)

  private val runCols = SqlFragment(
    """id::text, source_connection_id::text, run_type, status,
       started_at, completed_at, stats::text, log_lines::text"""
  )

  private def rowToRun(row: RunRow): SyncRun =
    val (id, sourceConnectionId, runType, status,
         startedAt, completedAt, statsStr, logLinesStr) = row
    val stats: Option[JsonObject] =
      statsStr.flatMap(s => circeParser.parse(s).toOption.flatMap(_.asObject))
    val logLines: Json =
      circeParser.parse(logLinesStr).toOption.getOrElse(Json.arr())
    SyncRun(
      id                 = UUID.fromString(id),
      sourceConnectionId = UUID.fromString(sourceConnectionId),
      runType            = runType,
      status             = status,
      startedAt          = startedAt.toInstant,
      completedAt        = completedAt.map(_.toInstant),
      stats              = stats,
      logLines           = logLines,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL against PostgreSQL via zio-jdbc. */
  final class Live extends SyncRunRepository:

    def create(run: SyncRun): ZIO[ZConnectionPool, AppError, SyncRun] =
      val startedTs   = java.sql.Timestamp.from(run.startedAt)
      val completedTs = run.completedAt.map(java.sql.Timestamp.from)
      val statsStr    = run.stats.map(_.asJson.noSpaces)
      val logLinesStr = run.logLines.noSpaces
      val q =
        sql"INSERT INTO sync_runs(id, source_connection_id, run_type, status, started_at, completed_at, stats, log_lines) " ++
        sql"VALUES (${run.id.toString}::uuid, ${run.sourceConnectionId.toString}::uuid, " ++
        sql"${run.runType}, ${run.status}, $startedTs, $completedTs, " ++
        sql"${statsStr}::jsonb, ${logLinesStr}::jsonb) " ++
        sql"RETURNING " ++ runCols
      transaction(q.query[RunRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT sync_runs returned no row"))))
        .map(rowToRun)

    def findByConnectionId(
        sourceConnectionId: UUID,
        limit:              Int,
    ): ZIO[ZConnectionPool, AppError, List[SyncRun]] =
      val q = sql"SELECT " ++ runCols ++
              sql" FROM sync_runs " ++
              sql" WHERE source_connection_id = ${sourceConnectionId.toString}::uuid " ++
              sql" ORDER BY started_at DESC LIMIT $limit"
      transaction(q.query[RunRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToRun))

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[SyncRun]] =
      val q = sql"SELECT " ++ runCols ++
              sql" FROM sync_runs WHERE id = ${id.toString}::uuid"
      transaction(q.query[RunRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToRun))

    def findLatest(sourceConnectionId: UUID): ZIO[ZConnectionPool, AppError, LatestSyncRuns] =
      val qAdhoc =
        sql"SELECT " ++ runCols ++
        sql" FROM sync_runs " ++
        sql" WHERE source_connection_id = ${sourceConnectionId.toString}::uuid " ++
        sql"   AND run_type = 'adhoc' " ++
        sql" ORDER BY started_at DESC LIMIT 1"
      val qScheduled =
        sql"SELECT " ++ runCols ++
        sql" FROM sync_runs " ++
        sql" WHERE source_connection_id = ${sourceConnectionId.toString}::uuid " ++
        sql"   AND run_type = 'scheduled' " ++
        sql" ORDER BY started_at DESC LIMIT 1"
      for
        adhoc     <- transaction(qAdhoc.query[RunRow].selectOne).mapError(mapSqlError)
        scheduled <- transaction(qScheduled.query[RunRow].selectOne).mapError(mapSqlError)
      yield LatestSyncRuns(
        lastAdhoc     = adhoc.map(rowToRun),
        lastScheduled = scheduled.map(rowToRun),
      )

    def patch(
        runId:              UUID,
        sourceConnectionId: UUID,
        req:                PatchSyncRunRequest,
    ): ZIO[ZConnectionPool, AppError, Option[SyncRun]] =
      val assignments: List[SqlFragment] = List.concat(
        req.status.map(s => sql"status = $s"),
        req.completedAt.map(ts => sql"completed_at = ${java.sql.Timestamp.from(ts)}"),
        req.stats.map(s => sql"stats = ${s.asJson.noSpaces}::jsonb"),
        req.logLines.map(l => sql"log_lines = ${l.noSpaces}::jsonb"),
      )
      if assignments.isEmpty then
        findById(runId).map(_.filter(_.sourceConnectionId == sourceConnectionId))
      else
        val setFrag = assignments.reduce(_ ++ SqlFragment(", ") ++ _)
        val q = sql"UPDATE sync_runs SET " ++ setFrag ++
                sql" WHERE id = ${runId.toString}::uuid " ++
                sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid " ++
                sql" RETURNING " ++ runCols
        transaction(q.query[RunRow].selectOne)
          .mapError(mapSqlError)
          .map(_.map(rowToRun))

  /** ZLayer providing the live SyncRunRepository. */
  val live: ZLayer[Any, Nothing, SyncRunRepository] =
    ZLayer.succeed(new Live)
