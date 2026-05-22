package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{
  AdvanceNextRunRequest,
  CreateSourceConnectionRequest,
  MarkSyncedRequest,
  PatchSyncRunRequest,
  PendingAdhocItem,
  SyncQueuedResponse,
  UpdateSourceConnectionRequest,
}
import com.myassistant.services.SourceConnectionService
import io.circe.Json
import io.circe.parser as circeParser
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for source_connections + sync_runs.
 *
 *  Path layout:
 *    POST   /api/v1/source-connections
 *    GET    /api/v1/source-connections                              ?personId= | ?householdId=
 *    GET    /api/v1/source-connections/due                          scheduler-internal
 *    GET    /api/v1/source-connections/{id}
 *    GET    /api/v1/source-connections/{id}/secrets                 scheduler-internal
 *    POST   /api/v1/source-connections/{id}/advance                 scheduler-internal
 *    POST   /api/v1/source-connections/{id}/mark-synced             scheduler-internal
 *    POST   /api/v1/source-connections/{id}/runs/create-scheduled   scheduler-internal
 *    PATCH  /api/v1/source-connections/{id}/runs/{run_id}           scheduler-internal
 *    PUT    /api/v1/source-connections/{id}
 *    DELETE /api/v1/source-connections/{id}
 *    POST   /api/v1/source-connections/{id}/sync
 *    GET    /api/v1/source-connections/{id}/runs                    ?limit=
 *    GET    /api/v1/source-connections/{id}/runs/latest
 *    GET    /api/v1/source-connections/{id}/runs/{run_id}
 *
 *  Note: the `due` and `latest` literal routes MUST be declared before
 *  their `{id}` / `{run_id}` parameterised counterparts so zio-http
 *  picks the literal first.
 */
object SourceConnectionRoutes:

  private val DefaultRunsLimit = 20

  val routes: Routes[SourceConnectionService & ZConnectionPool, Nothing] =
    Routes(

      // ── POST /api/v1/source-connections ───────────────────────
      Method.POST / "api" / "v1" / "source-connections" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateSourceConnectionRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  Json.obj(
                    "error"   -> Json.fromString("bad_request"),
                    "message" -> Json.fromString(err.getMessage),
                  ).noSpaces
                ).status(Status.BadRequest))
              case Right(createReq) =>
                ZIO.serviceWithZIO[SourceConnectionService](_.create(createReq))
                  .foldZIO(
                    err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    conn => ZIO.succeed(Response.json(conn.asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      // ── GET /api/v1/source-connections?personId|householdId ──
      Method.GET / "api" / "v1" / "source-connections" ->
        handler { (req: Request) =>
          val personIdResult    = req.queryParam("personId").map(s => Try(UUID.fromString(s)).toEither.left.map(_ => s))
          val householdIdResult = req.queryParam("householdId").map(s => Try(UUID.fromString(s)).toEither.left.map(_ => s))
          (personIdResult, householdIdResult) match
            case (Some(Left(bad)), _) =>
              ZIO.succeed(Response.json(Json.obj(
                "error"   -> Json.fromString("bad_request"),
                "message" -> Json.fromString(s"Invalid UUID: $bad"),
              ).noSpaces).status(Status.BadRequest))
            case (_, Some(Left(bad))) =>
              ZIO.succeed(Response.json(Json.obj(
                "error"   -> Json.fromString("bad_request"),
                "message" -> Json.fromString(s"Invalid UUID: $bad"),
              ).noSpaces).status(Status.BadRequest))
            case (Some(Right(personId)), _) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.listByPerson(personId))
                .foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  conns => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(conns.map(_.asJson)*)).noSpaces)),
                )
            case (_, Some(Right(householdId))) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.listByHousehold(householdId))
                .foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  conns => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(conns.map(_.asJson)*)).noSpaces)),
                )
            case (None, None) =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString("Query parameter 'personId' or 'householdId' is required"),
                ).noSpaces
              ).status(Status.BadRequest))
        },

      // ── GET /api/v1/source-connections/due ───────────────────
      // MUST come before /{id} so the literal wins.
      Method.GET / "api" / "v1" / "source-connections" / "due" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[SourceConnectionService](_.listDue())
            .foldZIO(
              err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              conns => ZIO.succeed(Response.json(
                Json.obj("items" -> Json.arr(conns.map(_.asJson)*)).noSpaces
              )),
            )
        },

      // ── GET /api/v1/source-connections/adhoc-pending ─────────
      // Scheduler-internal — returns connections with pending adhoc runs.
      // MUST come before /{id} so the literal wins.
      Method.GET / "api" / "v1" / "source-connections" / "adhoc-pending" ->
        handler { (_: Request) =>
          ZIO.serviceWithZIO[SourceConnectionService](_.listPendingAdhoc())
            .foldZIO(
              err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              items => ZIO.succeed(Response.json(
                Json.obj("items" -> Json.arr(
                  items.map { case (conn, runId) => PendingAdhocItem(conn, runId).asJson }*
                )).noSpaces
              )),
            )
        },

      // ── GET /api/v1/source-connections/{id} ──────────────────
      Method.GET / "api" / "v1" / "source-connections" / string("id") ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.getById(uid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  {
                    case None       => ZIO.succeed(Response.json(
                        Json.obj(
                          "error"   -> Json.fromString("not_found"),
                          "message" -> Json.fromString(s"source_connection with id '$uid' not found"),
                        ).noSpaces
                      ).status(Status.NotFound))
                    case Some(conn) => ZIO.succeed(Response.json(conn.asJson.noSpaces))
                  },
                )
        },

      // ── GET /api/v1/source-connections/{id}/secrets ──────────
      // Scheduler-internal — returns decrypted JSON.
      Method.GET / "api" / "v1" / "source-connections" / string("id") / "secrets" ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.getSecrets(uid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  secretsOpt =>
                    val secretsJson = secretsOpt match
                      case None    => Json.Null
                      case Some(s) => circeParser.parse(s).getOrElse(Json.Null)
                    ZIO.succeed(Response.json(Json.obj("secrets" -> secretsJson).noSpaces)),
                )
        },

      // ── POST /api/v1/source-connections/{id}/advance ─────────
      // Scheduler-internal — advance next_run_at after cron tick.
      Method.POST / "api" / "v1" / "source-connections" / string("id") / "advance" ->
        handler { (id: String, req: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[AdvanceNextRunRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      Json.obj(
                        "error"   -> Json.fromString("bad_request"),
                        "message" -> Json.fromString(err.getMessage),
                      ).noSpaces
                    ).status(Status.BadRequest))
                  case Right(advReq) =>
                    ZIO.serviceWithZIO[SourceConnectionService](_.advanceNextRun(uid, advReq.nextRunAt))
                      .foldZIO(
                        err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        found =>
                          if found then ZIO.succeed(Response.status(Status.NoContent))
                          else ZIO.succeed(Response.json(
                            Json.obj(
                              "error"   -> Json.fromString("not_found"),
                              "message" -> Json.fromString(s"source_connection with id '$uid' not found"),
                            ).noSpaces
                          ).status(Status.NotFound)),
                      )
              yield response
        },

      // ── POST /api/v1/source-connections/{id}/mark-synced ─────
      // Scheduler-internal — set last_synced_at after a sync run.
      Method.POST / "api" / "v1" / "source-connections" / string("id") / "mark-synced" ->
        handler { (id: String, req: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[MarkSyncedRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      Json.obj(
                        "error"   -> Json.fromString("bad_request"),
                        "message" -> Json.fromString(err.getMessage),
                      ).noSpaces
                    ).status(Status.BadRequest))
                  case Right(msReq) =>
                    ZIO.serviceWithZIO[SourceConnectionService](_.markSynced(uid, msReq.lastSyncedAt))
                      .foldZIO(
                        err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        found =>
                          if found then ZIO.succeed(Response.status(Status.NoContent))
                          else ZIO.succeed(Response.json(
                            Json.obj(
                              "error"   -> Json.fromString("not_found"),
                              "message" -> Json.fromString(s"source_connection with id '$uid' not found"),
                            ).noSpaces
                          ).status(Status.NotFound)),
                      )
              yield response
        },

      // ── POST /api/v1/source-connections/{id}/runs/create-scheduled ─────
      // Scheduler-internal — insert a sync_runs row with run_type='scheduled', status='running'.
      // MUST come before the parameterised {runId} route.
      Method.POST / "api" / "v1" / "source-connections" / string("id") / "runs" / "create-scheduled" ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.createScheduledRun(uid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  run => ZIO.succeed(Response.json(run.asJson.noSpaces).status(Status.Created)),
                )
        },

      // ── PATCH /api/v1/source-connections/{id}/runs/{run_id} ──
      // Scheduler-internal — record terminal status / stats / log lines.
      Method.PATCH / "api" / "v1" / "source-connections" / string("id") / "runs" / string("runId") ->
        handler { (id: String, runId: String, req: Request) =>
          val parsed =
            for
              cid <- Try(UUID.fromString(id)).toEither.left.map(_ => s"id=$id")
              rid <- Try(UUID.fromString(runId)).toEither.left.map(_ => s"runId=$runId")
            yield (cid, rid)
          parsed match
            case Left(bad)         =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $bad"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right((cid, rid)) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[PatchSyncRunRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      Json.obj(
                        "error"   -> Json.fromString("bad_request"),
                        "message" -> Json.fromString(err.getMessage),
                      ).noSpaces
                    ).status(Status.BadRequest))
                  case Right(patchReq) =>
                    ZIO.serviceWithZIO[SourceConnectionService](_.patchRun(cid, rid, patchReq))
                      .foldZIO(
                        err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        {
                          case None      => ZIO.succeed(Response.json(
                              Json.obj(
                                "error"   -> Json.fromString("not_found"),
                                "message" -> Json.fromString(s"sync_run with id '$rid' not found"),
                              ).noSpaces
                            ).status(Status.NotFound))
                          case Some(run) => ZIO.succeed(Response.json(run.asJson.noSpaces))
                        },
                      )
              yield response
        },

      // ── PUT /api/v1/source-connections/{id} ──────────────────
      Method.PUT / "api" / "v1" / "source-connections" / string("id") ->
        handler { (id: String, req: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[UpdateSourceConnectionRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      Json.obj(
                        "error"   -> Json.fromString("bad_request"),
                        "message" -> Json.fromString(err.getMessage),
                      ).noSpaces
                    ).status(Status.BadRequest))
                  case Right(updateReq) =>
                    ZIO.serviceWithZIO[SourceConnectionService](_.update(uid, updateReq))
                      .foldZIO(
                        err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        {
                          case None       => ZIO.succeed(Response.json(
                              Json.obj(
                                "error"   -> Json.fromString("not_found"),
                                "message" -> Json.fromString(s"source_connection with id '$uid' not found"),
                              ).noSpaces
                            ).status(Status.NotFound))
                          case Some(conn) => ZIO.succeed(Response.json(conn.asJson.noSpaces))
                        },
                      )
              yield response
        },

      // ── DELETE /api/v1/source-connections/{id} ───────────────
      Method.DELETE / "api" / "v1" / "source-connections" / string("id") ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.delete(uid))
                .foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  found =>
                    if found then ZIO.succeed(Response.status(Status.NoContent))
                    else ZIO.succeed(Response.json(
                      Json.obj(
                        "error"   -> Json.fromString("not_found"),
                        "message" -> Json.fromString(s"source_connection with id '$uid' not found"),
                      ).noSpaces
                    ).status(Status.NotFound)),
                )
        },

      // ── POST /api/v1/source-connections/{id}/sync ────────────
      // Body is ignored (the contract allows '{}') — we just need
      // to insert a sync_runs row with runType='adhoc', status='running'.
      Method.POST / "api" / "v1" / "source-connections" / string("id") / "sync" ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.triggerSync(uid))
                .foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  cid   => ZIO.succeed(
                    Response.json(
                      SyncQueuedResponse(message = "sync queued", connectionId = cid).asJson.noSpaces
                    ).status(Status.Accepted)
                  ),
                )
        },

      // ── GET /api/v1/source-connections/{id}/runs/latest ──────
      // MUST come before the {runId} variant so the literal wins.
      Method.GET / "api" / "v1" / "source-connections" / string("id") / "runs" / "latest" ->
        handler { (id: String, _: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.getLatestRuns(uid))
                .foldZIO(
                  err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  latest => ZIO.succeed(Response.json(latest.asJson.noSpaces)),
                )
        },

      // ── GET /api/v1/source-connections/{id}/runs ─────────────
      Method.GET / "api" / "v1" / "source-connections" / string("id") / "runs" ->
        handler { (id: String, req: Request) =>
          Try(UUID.fromString(id)).toEither match
            case Left(_)    =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $id"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right(uid) =>
              val limitOpt: Either[String, Int] = req.queryParam("limit") match
                case None      => Right(DefaultRunsLimit)
                case Some(str) => Try(str.toInt).toEither.left.map(_ => str)
              limitOpt match
                case Left(bad) =>
                  ZIO.succeed(Response.json(
                    Json.obj(
                      "error"   -> Json.fromString("bad_request"),
                      "message" -> Json.fromString(s"Invalid integer for 'limit': $bad"),
                    ).noSpaces
                  ).status(Status.BadRequest))
                case Right(limit) =>
                  ZIO.serviceWithZIO[SourceConnectionService](_.getRunsByConnectionId(uid, limit))
                    .foldZIO(
                      err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                      runs => ZIO.succeed(Response.json(io.circe.Json.obj("items" -> io.circe.Json.arr(runs.map(_.asJson)*)).noSpaces)),
                    )
        },

      // ── GET /api/v1/source-connections/{id}/runs/{run_id} ────
      Method.GET / "api" / "v1" / "source-connections" / string("id") / "runs" / string("runId") ->
        handler { (id: String, runId: String, _: Request) =>
          val parsed =
            for
              cid <- Try(UUID.fromString(id)).toEither.left.map(_ => s"id=$id")
              rid <- Try(UUID.fromString(runId)).toEither.left.map(_ => s"runId=$runId")
            yield (cid, rid)
          parsed match
            case Left(bad)         =>
              ZIO.succeed(Response.json(
                Json.obj(
                  "error"   -> Json.fromString("bad_request"),
                  "message" -> Json.fromString(s"Invalid UUID: $bad"),
                ).noSpaces
              ).status(Status.BadRequest))
            case Right((cid, rid)) =>
              ZIO.serviceWithZIO[SourceConnectionService](_.getRunById(cid, rid))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  {
                    case None      => ZIO.succeed(Response.json(
                        Json.obj(
                          "error"   -> Json.fromString("not_found"),
                          "message" -> Json.fromString(s"sync_run with id '$rid' not found"),
                        ).noSpaces
                      ).status(Status.NotFound))
                    case Some(run) => ZIO.succeed(Response.json(run.asJson.noSpaces))
                  },
                )
        },
    )
