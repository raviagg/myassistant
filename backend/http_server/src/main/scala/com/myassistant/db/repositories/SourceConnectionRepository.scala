package com.myassistant.db.repositories

import com.myassistant.domain.{CreateSourceConnection, SourceConnection, UpdateSourceConnection}
import com.myassistant.errors.AppError
import io.circe.JsonObject
import io.circe.parser as circeParser
import io.circe.syntax.*
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Data-access interface for the `source_connections` table. */
trait SourceConnectionRepository:

  /** Insert a new connection and return the persisted record. */
  def create(req: CreateSourceConnection): ZIO[ZConnectionPool, AppError, SourceConnection]

  /** Fetch a connection by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[SourceConnection]]

  /** List all connections belonging to a specific person. */
  def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnection]]

  /** List all connections belonging to a specific household. */
  def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnection]]

  /** List enabled, scheduled connections whose next_run_at is due.
   *  Reserved for the connector scheduler — not exposed via the API routes.
   */
  def findDue(): ZIO[ZConnectionPool, AppError, List[SourceConnection]]

  /** Full update of a connection. When `secretsCiphertext` is None,
   *  the stored secrets column is preserved as-is.
   */
  def update(id: UUID, req: UpdateSourceConnection): ZIO[ZConnectionPool, AppError, Option[SourceConnection]]

  /** Update the next_run_at scheduling timestamp.
   *  Reserved for the connector scheduler — not exposed via the API routes.
   */
  def updateNextRunAt(id: UUID, nextRunAt: Option[Instant]): ZIO[ZConnectionPool, AppError, Unit]

  /** Update the last_synced_at timestamp after a run completes.
   *  Reserved for the connector scheduler — not exposed via the API routes.
   */
  def updateLastSyncedAt(id: UUID, lastSyncedAt: Instant): ZIO[ZConnectionPool, AppError, Unit]

  /** Update the lifecycle status (active / paused / error).
   *  Reserved for the connector scheduler — not exposed via the API routes.
   */
  def updateStatus(id: UUID, status: String): ZIO[ZConnectionPool, AppError, Unit]

  /** Delete a connection (cascades to sync_runs). Returns true if a row was removed. */
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

object SourceConnectionRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, source_type, connection_name, person_id, household_id, config,
  // has_secrets, sync_scheduled, sync_adhoc, sync_schedule,
  // next_run_at, last_synced_at, status, created_at, updated_at
  private type ConnRow =
    (String, String, String, Option[String], Option[String], String,
     Boolean, Boolean, Boolean, Option[String],
     Option[java.sql.Timestamp], Option[java.sql.Timestamp],
     String, java.sql.Timestamp, java.sql.Timestamp)

  /** Column list for SELECT/RETURNING. `secrets IS NOT NULL` is projected
   *  as a boolean so the domain model can expose `hasSecrets` without ever
   *  surfacing the encrypted blob.
   */
  private val connCols = SqlFragment(
    """id::text, source_type, connection_name,
       person_id::text, household_id::text,
       config::text,
       (secrets IS NOT NULL) AS has_secrets,
       sync_scheduled, sync_adhoc, sync_schedule,
       next_run_at, last_synced_at,
       status, created_at, updated_at"""
  )

  private def rowToConn(row: ConnRow): SourceConnection =
    val (id, sourceType, connectionName, personId, householdId, configStr,
         hasSecrets, syncScheduled, syncAdhoc, syncSchedule,
         nextRunAt, lastSyncedAt, status, createdAt, updatedAt) = row
    val config = circeParser.parse(configStr)
      .toOption
      .flatMap(_.asObject)
      .getOrElse(JsonObject.empty)
    SourceConnection(
      id             = UUID.fromString(id),
      sourceType     = sourceType,
      connectionName = connectionName,
      personId       = personId.map(UUID.fromString),
      householdId    = householdId.map(UUID.fromString),
      config         = config,
      hasSecrets     = hasSecrets,
      syncScheduled  = syncScheduled,
      syncAdhoc      = syncAdhoc,
      syncSchedule   = syncSchedule,
      nextRunAt      = nextRunAt.map(_.toInstant),
      lastSyncedAt   = lastSyncedAt.map(_.toInstant),
      status         = status,
      createdAt      = createdAt.toInstant,
      updatedAt      = updatedAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL against PostgreSQL via zio-jdbc. */
  final class Live extends SourceConnectionRepository:

    def create(req: CreateSourceConnection): ZIO[ZConnectionPool, AppError, SourceConnection] =
      val id        = UUID.randomUUID()
      val configStr = req.config.asJson.noSpaces
      val q =
        sql"INSERT INTO source_connections(id, source_type, connection_name, person_id, household_id, config, secrets, sync_scheduled, sync_adhoc, sync_schedule) " ++
        sql"VALUES (${id.toString}::uuid, ${req.sourceType}, ${req.connectionName}, " ++
        sql"${req.personId.map(_.toString)}::uuid, ${req.householdId.map(_.toString)}::uuid, " ++
        sql"${configStr}::jsonb, ${req.secretsCiphertext}, " ++
        sql"${req.syncScheduled}, ${req.syncAdhoc}, ${req.syncSchedule}) " ++
        sql"RETURNING " ++ connCols
      transaction(q.query[ConnRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT source_connections returned no row"))))
        .map(rowToConn)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[SourceConnection]] =
      val q = sql"SELECT " ++ connCols ++
              sql" FROM source_connections WHERE id = ${id.toString}::uuid"
      transaction(q.query[ConnRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToConn))

    def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnection]] =
      val q = sql"SELECT " ++ connCols ++
              sql" FROM source_connections WHERE person_id = ${personId.toString}::uuid ORDER BY created_at DESC"
      transaction(q.query[ConnRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToConn))

    def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnection]] =
      val q = sql"SELECT " ++ connCols ++
              sql" FROM source_connections WHERE household_id = ${householdId.toString}::uuid ORDER BY created_at DESC"
      transaction(q.query[ConnRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToConn))

    def findDue(): ZIO[ZConnectionPool, AppError, List[SourceConnection]] =
      val q = sql"SELECT " ++ connCols ++
              sql" FROM source_connections " ++
              sql" WHERE sync_scheduled = true AND status = 'active' " ++
              sql"   AND (next_run_at IS NULL OR next_run_at <= NOW()) " ++
              sql" ORDER BY next_run_at ASC NULLS FIRST"
      transaction(q.query[ConnRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToConn))

    def update(id: UUID, req: UpdateSourceConnection): ZIO[ZConnectionPool, AppError, Option[SourceConnection]] =
      val configStr = req.config.asJson.noSpaces

      // Always overwrite all fields except secrets, which is only
      // overwritten when a new ciphertext was supplied.
      val baseAssignments: List[SqlFragment] = List(
        sql"source_type = ${req.sourceType}",
        sql"connection_name = ${req.connectionName}",
        sql"person_id = ${req.personId.map(_.toString)}::uuid",
        sql"household_id = ${req.householdId.map(_.toString)}::uuid",
        sql"config = ${configStr}::jsonb",
        sql"sync_scheduled = ${req.syncScheduled}",
        sql"sync_adhoc = ${req.syncAdhoc}",
        sql"sync_schedule = ${req.syncSchedule}",
      )
      val assignments = req.secretsCiphertext match
        case Some(ct) => baseAssignments :+ sql"secrets = ${ct}"
        case None     => baseAssignments
      val setFrag = assignments.reduce(_ ++ SqlFragment(", ") ++ _)
      val q = sql"UPDATE source_connections SET " ++ setFrag ++
              sql" WHERE id = ${id.toString}::uuid RETURNING " ++ connCols
      transaction(q.query[ConnRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToConn))

    def updateNextRunAt(id: UUID, nextRunAt: Option[Instant]): ZIO[ZConnectionPool, AppError, Unit] =
      val ts = nextRunAt.map(java.sql.Timestamp.from)
      val q  = sql"UPDATE source_connections SET next_run_at = $ts WHERE id = ${id.toString}::uuid"
      transaction(q.update)
        .mapError(mapSqlError)
        .unit

    def updateLastSyncedAt(id: UUID, lastSyncedAt: Instant): ZIO[ZConnectionPool, AppError, Unit] =
      val ts = java.sql.Timestamp.from(lastSyncedAt)
      val q  = sql"UPDATE source_connections SET last_synced_at = $ts WHERE id = ${id.toString}::uuid"
      transaction(q.update)
        .mapError(mapSqlError)
        .unit

    def updateStatus(id: UUID, status: String): ZIO[ZConnectionPool, AppError, Unit] =
      val q = sql"UPDATE source_connections SET status = $status WHERE id = ${id.toString}::uuid"
      transaction(q.update)
        .mapError(mapSqlError)
        .unit

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction(
        sql"DELETE FROM source_connections WHERE id = ${id.toString}::uuid".delete
      ).mapError(mapSqlError)
        .map(_ > 0)

  /** ZLayer providing the live SourceConnectionRepository. */
  val live: ZLayer[Any, Nothing, SourceConnectionRepository] =
    ZLayer.succeed(new Live)
