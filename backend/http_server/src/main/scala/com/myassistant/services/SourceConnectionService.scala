package com.myassistant.services

import com.myassistant.api.models.{
  CreateSourceConnectionRequest,
  LatestSyncRunsResponse,
  SourceConnectionResponse,
  SyncRunResponse,
  UpdateSourceConnectionRequest,
}
import com.myassistant.config.SecretsConfig
import com.myassistant.db.repositories.{SourceConnectionRepository, SyncRunRepository}
import com.myassistant.domain.{CreateSourceConnection, SyncRun, UpdateSourceConnection}
import com.myassistant.errors.AppError
import io.circe.{Json, JsonObject}
import zio.*
import zio.jdbc.*

import java.time.Instant
import java.util.UUID

/** Business logic for source connections — owns validation, secrets
 *  encryption, and orchestration of the sync_runs side-effects.
 *
 *  The service NEVER surfaces the encrypted secrets blob to callers;
 *  responses always carry the `SourceConnectionResponse` shape which
 *  has no `secrets` field at all.
 */
trait SourceConnectionService:

  def create(req: CreateSourceConnectionRequest): ZIO[ZConnectionPool, AppError, SourceConnectionResponse]

  def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnectionResponse]]

  def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnectionResponse]]

  def getById(id: UUID): ZIO[ZConnectionPool, AppError, Option[SourceConnectionResponse]]

  def update(
      id:  UUID,
      req: UpdateSourceConnectionRequest,
  ): ZIO[ZConnectionPool, AppError, Option[SourceConnectionResponse]]

  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

  /** Trigger an adhoc sync run: insert a sync_runs row with
   *  runType='adhoc', status='running' and return the connection id.
   *  The actual sync execution is performed asynchronously by the
   *  connector worker (out of scope here).
   */
  def triggerSync(id: UUID): ZIO[ZConnectionPool, AppError, UUID]

  def getRunsByConnectionId(
      id:    UUID,
      limit: Int,
  ): ZIO[ZConnectionPool, AppError, List[SyncRunResponse]]

  def getLatestRuns(id: UUID): ZIO[ZConnectionPool, AppError, LatestSyncRunsResponse]

  def getRunById(
      connectionId: UUID,
      runId:        UUID,
  ): ZIO[ZConnectionPool, AppError, Option[SyncRunResponse]]

object SourceConnectionService:

  final class Live(
      connRepo:      SourceConnectionRepository,
      runRepo:       SyncRunRepository,
      secretsConfig: SecretsConfig,
  ) extends SourceConnectionService:

    // ── helpers ───────────────────────────────────────────────────────────

    private val ValidStatuses    = Set("active", "paused", "error")
    private val ValidSourceTypes =
      Set("plaid_poll", "gmail_poll", "news_poll", "chatbot", "bulk_file", "bulk_image")

    private def encryptIfPresent(secrets: Option[String]): IO[AppError, Option[String]] =
      secrets.filter(_.nonEmpty) match
        case None     => ZIO.succeed(None)
        case Some(pt) =>
          ZIO.fromEither(SecretsService.encrypt(pt, secretsConfig))
            .mapBoth(AppError.InternalError(_), Some(_))

    /** Cross-field validation common to create + update. */
    private def validateOwnership(personId: Option[UUID], householdId: Option[UUID]): IO[AppError, Unit] =
      (personId.isDefined, householdId.isDefined) match
        case (false, false) =>
          ZIO.fail(AppError.ValidationError(
            "A source connection must be scoped to a person or a household"))
        case (true, true) =>
          ZIO.fail(AppError.ValidationError(
            "A source connection cannot be scoped to both a person and a household"))
        case _ => ZIO.unit

    private def validateSchedule(syncScheduled: Boolean, syncSchedule: Option[String]): IO[AppError, Unit] =
      (syncScheduled, syncSchedule.exists(_.nonEmpty)) match
        case (true, false) =>
          ZIO.fail(AppError.ValidationError(
            "syncSchedule is required when syncScheduled is true"))
        case (false, true) =>
          ZIO.fail(AppError.ValidationError(
            "syncSchedule must be null when syncScheduled is false"))
        case _ => ZIO.unit

    // ── CRUD ──────────────────────────────────────────────────────────────

    def create(req: CreateSourceConnectionRequest): ZIO[ZConnectionPool, AppError, SourceConnectionResponse] =
      val syncScheduled = req.syncScheduled.getOrElse(false)
      val syncAdhoc     = req.syncAdhoc.getOrElse(true)
      val syncSchedule  = req.syncSchedule.filter(_.nonEmpty)
      for
        _ <- ZIO.unless(ValidSourceTypes.contains(req.sourceType))(
               ZIO.fail(AppError.ValidationError(
                 s"sourceType must be one of: ${ValidSourceTypes.toList.sorted.mkString(", ")}")))
        _ <- validateOwnership(req.personId, req.householdId)
        _ <- validateSchedule(syncScheduled, syncSchedule)
        ct <- encryptIfPresent(req.secrets)
        domainReq = CreateSourceConnection(
                      sourceType        = req.sourceType,
                      connectionName    = req.connectionName,
                      personId          = req.personId,
                      householdId       = req.householdId,
                      config            = req.config.getOrElse(JsonObject.empty),
                      secretsCiphertext = ct,
                      syncScheduled     = syncScheduled,
                      syncAdhoc         = syncAdhoc,
                      syncSchedule      = syncSchedule,
                    )
        created <- connRepo.create(domainReq)
      yield SourceConnectionResponse.fromDomain(created)

    def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnectionResponse]] =
      connRepo.listByPerson(personId).map(_.map(SourceConnectionResponse.fromDomain))

    def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[SourceConnectionResponse]] =
      connRepo.listByHousehold(householdId).map(_.map(SourceConnectionResponse.fromDomain))

    def getById(id: UUID): ZIO[ZConnectionPool, AppError, Option[SourceConnectionResponse]] =
      connRepo.findById(id).map(_.map(SourceConnectionResponse.fromDomain))

    def update(
        id:  UUID,
        req: UpdateSourceConnectionRequest,
    ): ZIO[ZConnectionPool, AppError, Option[SourceConnectionResponse]] =
      val syncScheduled = req.syncScheduled.getOrElse(false)
      val syncAdhoc     = req.syncAdhoc.getOrElse(true)
      val syncSchedule  = req.syncSchedule.filter(_.nonEmpty)
      for
        _ <- ZIO.unless(ValidSourceTypes.contains(req.sourceType))(
               ZIO.fail(AppError.ValidationError(
                 s"sourceType must be one of: ${ValidSourceTypes.toList.sorted.mkString(", ")}")))
        _ <- validateOwnership(req.personId, req.householdId)
        _ <- validateSchedule(syncScheduled, syncSchedule)
        // Blank / null secrets preserve the existing encrypted value.
        ct <- encryptIfPresent(req.secrets)
        domainReq = UpdateSourceConnection(
                      sourceType        = req.sourceType,
                      connectionName    = req.connectionName,
                      personId          = req.personId,
                      householdId       = req.householdId,
                      config            = req.config.getOrElse(JsonObject.empty),
                      secretsCiphertext = ct,
                      syncScheduled     = syncScheduled,
                      syncAdhoc         = syncAdhoc,
                      syncSchedule      = syncSchedule,
                    )
        updated <- connRepo.update(id, domainReq)
      yield updated.map(SourceConnectionResponse.fromDomain)

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      connRepo.delete(id)

    // ── sync trigger + run history ────────────────────────────────────────

    def triggerSync(id: UUID): ZIO[ZConnectionPool, AppError, UUID] =
      connRepo.findById(id).flatMap {
        case None       =>
          ZIO.fail(AppError.NotFound("source_connection", id.toString))
        case Some(conn) =>
          if !conn.syncAdhoc then
            ZIO.fail(AppError.Conflict(
              s"source_connection '$id' does not support adhoc sync (syncAdhoc=false)"))
          else
            val run = SyncRun(
              id                 = UUID.randomUUID(),
              sourceConnectionId = id,
              runType            = "adhoc",
              status             = "running",
              startedAt          = Instant.now(),
              completedAt        = None,
              stats              = None,
              logLines           = Json.arr(),
            )
            runRepo.create(run).as(id)
      }

    def getRunsByConnectionId(
        id:    UUID,
        limit: Int,
    ): ZIO[ZConnectionPool, AppError, List[SyncRunResponse]] =
      connRepo.findById(id).flatMap {
        case None    => ZIO.fail(AppError.NotFound("source_connection", id.toString))
        case Some(_) =>
          runRepo.findByConnectionId(id, limit).map(_.map(SyncRunResponse.fromDomain))
      }

    def getLatestRuns(id: UUID): ZIO[ZConnectionPool, AppError, LatestSyncRunsResponse] =
      connRepo.findById(id).flatMap {
        case None    => ZIO.fail(AppError.NotFound("source_connection", id.toString))
        case Some(_) => runRepo.findLatest(id).map(LatestSyncRunsResponse.fromDomain)
      }

    def getRunById(
        connectionId: UUID,
        runId:        UUID,
    ): ZIO[ZConnectionPool, AppError, Option[SyncRunResponse]] =
      runRepo.findById(runId).map(_.filter(_.sourceConnectionId == connectionId).map(SyncRunResponse.fromDomain))

  val live: ZLayer[SourceConnectionRepository & SyncRunRepository & SecretsConfig, Nothing, SourceConnectionService] =
    ZLayer.fromFunction(new Live(_, _, _))
