package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{
  CreateSourceConnectionRequest,
  SyncQueuedResponse,
  UpdateSourceConnectionRequest,
}
import com.myassistant.services.SourceConnectionService
import io.circe.Json
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
 *    GET    /api/v1/source-connections/{id}
 *    PUT    /api/v1/source-connections/{id}
 *    DELETE /api/v1/source-connections/{id}
 *    POST   /api/v1/source-connections/{id}/sync
 *    GET    /api/v1/source-connections/{id}/runs                    ?limit=
 *    GET    /api/v1/source-connections/{id}/runs/latest
 *    GET    /api/v1/source-connections/{id}/runs/{run_id}
 *
 *  Note: the `latest` literal route MUST be declared before the
 *  `{run_id}` parameterised route so zio-http picks the literal first.
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
