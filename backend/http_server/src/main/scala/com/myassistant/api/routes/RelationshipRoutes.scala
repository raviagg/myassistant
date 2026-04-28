package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateRelationshipRequest, KinshipResponse, PagedResponse, RelationshipResponse, UpdateRelationshipRequest, parseRelationType}
import com.myassistant.errors.AppError
import com.myassistant.services.{KinshipResolver, RelationshipService}
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for relationship management and kinship resolution.
 *
 *  POST   /api/v1/relationships                              — create a relationship
 *  GET    /api/v1/relationships                              — list (query: person_id)
 *  GET    /api/v1/relationships/:fromId/:toId                — get a relationship
 *  PATCH  /api/v1/relationships/:fromId/:toId                — update relation type
 *  DELETE /api/v1/relationships/:fromId/:toId                — delete a relationship
 *  GET    /api/v1/relationships/:fromId/:toId/kinship        — resolve kinship
 */
object RelationshipRoutes:

  /** Find the first relationship between two persons from a full list. */
  private def findByPair(fromId: UUID, toId: UUID, svc: RelationshipService) =
    svc.listRelationships(fromId).map(_.find(r => r.fromPersonId == fromId && r.toPersonId == toId))

  /** Build relationship and kinship routes requiring RelationshipService, KinshipResolver, and ZConnectionPool. */
  val routes: Routes[RelationshipService & KinshipResolver & ZConnectionPool, Nothing] =
    Routes(
      Method.POST / "api" / "v1" / "relationships" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateRelationshipRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                parseRelationType(createReq.relationType) match
                  case Left(msg) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"validation_error","message":"$msg"}"""
                    ).status(Status.UnprocessableEntity))
                  case Right(rt) =>
                    import com.myassistant.domain.CreateRelationship
                    ZIO.serviceWithZIO[RelationshipService](_.createRelationship(
                      CreateRelationship(createReq.fromPersonId, createReq.toPersonId, rt)
                    )).foldZIO(
                      err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                      rel => ZIO.succeed(Response.json(RelationshipResponse.fromDomain(rel).asJson.noSpaces).status(Status.Created)),
                    )
          yield response
        },

      Method.GET / "api" / "v1" / "relationships" ->
        handler { (req: Request) =>
          req.queryParam("person_id") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'person_id' is required"}"""
              ).status(Status.BadRequest))
            case Some(pidStr) =>
              Try(UUID.fromString(pidStr)).toEither match
                case Left(_)    =>
                  ZIO.succeed(Response.json(
                    s"""{"error":"bad_request","message":"Invalid UUID: $pidStr"}"""
                  ).status(Status.BadRequest))
                case Right(pid) =>
                  ZIO.serviceWithZIO[RelationshipService](_.listRelationships(pid))
                    .foldZIO(
                      err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                      rels =>
                        ZIO.succeed(Response.json(
                          PagedResponse(
                            items  = rels.map(RelationshipResponse.fromDomain),
                            total  = rels.size.toLong,
                            limit  = rels.size,
                            offset = 0,
                          ).asJson.noSpaces
                        ))
                    )
        },

      Method.GET / "api" / "v1" / "relationships" / string("fromId") / string("toId") ->
        handler { (fromIdStr: String, toIdStr: String, _: Request) =>
          (Try(UUID.fromString(fromIdStr)).toEither, Try(UUID.fromString(toIdStr)).toEither) match
            case (Left(_), _) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid fromId UUID: $fromIdStr"}"""
              ).status(Status.BadRequest))
            case (_, Left(_)) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid toId UUID: $toIdStr"}"""
              ).status(Status.BadRequest))
            case (Right(fromId), Right(toId)) =>
              ZIO.serviceWithZIO[RelationshipService](svc => findByPair(fromId, toId, svc))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  {
                    case None      => ZIO.succeed(Response.json(
                      s"""{"error":"not_found","message":"relationship '$fromId -> $toId' not found"}"""
                    ).status(Status.NotFound))
                    case Some(rel) => ZIO.succeed(Response.json(RelationshipResponse.fromDomain(rel).asJson.noSpaces))
                  }
                )
        },

      Method.PATCH / "api" / "v1" / "relationships" / string("fromId") / string("toId") ->
        handler { (fromIdStr: String, toIdStr: String, req: Request) =>
          (Try(UUID.fromString(fromIdStr)).toEither, Try(UUID.fromString(toIdStr)).toEither) match
            case (Left(_), _) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid fromId UUID: $fromIdStr"}"""
              ).status(Status.BadRequest))
            case (_, Left(_)) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid toId UUID: $toIdStr"}"""
              ).status(Status.BadRequest))
            case (Right(fromId), Right(toId)) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[UpdateRelationshipRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(updateReq) =>
                    parseRelationType(updateReq.relationType) match
                      case Left(msg) =>
                        ZIO.succeed(Response.json(
                          s"""{"error":"validation_error","message":"$msg"}"""
                        ).status(Status.UnprocessableEntity))
                      case Right(rt) =>
                        ZIO.serviceWithZIO[RelationshipService] { svc =>
                          findByPair(fromId, toId, svc).flatMap {
                            case None      =>
                              ZIO.fail(AppError.NotFound("relationship", s"$fromId -> $toId"))
                            case Some(rel) =>
                              svc.updateRelationship(rel.id, rt)
                          }
                        }.foldZIO(
                          err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                          updated => ZIO.succeed(Response.json(RelationshipResponse.fromDomain(updated).asJson.noSpaces)),
                        )
              yield response
        },

      Method.DELETE / "api" / "v1" / "relationships" / string("fromId") / string("toId") ->
        handler { (fromIdStr: String, toIdStr: String, _: Request) =>
          (Try(UUID.fromString(fromIdStr)).toEither, Try(UUID.fromString(toIdStr)).toEither) match
            case (Left(_), _) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid fromId UUID: $fromIdStr"}"""
              ).status(Status.BadRequest))
            case (_, Left(_)) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid toId UUID: $toIdStr"}"""
              ).status(Status.BadRequest))
            case (Right(fromId), Right(toId)) =>
              ZIO.serviceWithZIO[RelationshipService] { svc =>
                findByPair(fromId, toId, svc).flatMap {
                  case None      => ZIO.fail(AppError.NotFound("relationship", s"$fromId -> $toId"))
                  case Some(rel) => svc.deleteRelationship(rel.id)
                }
              }.foldZIO(
                err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                _   => ZIO.succeed(Response.status(Status.NoContent)),
              )
        },

      Method.GET / "api" / "v1" / "relationships" / string("fromId") / string("toId") / "kinship" ->
        handler { (fromIdStr: String, toIdStr: String, req: Request) =>
          (Try(UUID.fromString(fromIdStr)).toEither, Try(UUID.fromString(toIdStr)).toEither) match
            case (Left(_), _) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid fromId UUID: $fromIdStr"}"""
              ).status(Status.BadRequest))
            case (_, Left(_)) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid toId UUID: $toIdStr"}"""
              ).status(Status.BadRequest))
            case (Right(fromId), Right(toId)) =>
              val language = req.queryParam("language").getOrElse("english")
              ZIO.serviceWithZIO[KinshipResolver](_.resolve(fromId, toId, language))
                .foldZIO(
                  err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  {
                    case None         => ZIO.succeed(Response.json(
                      s"""{"error":"not_found","message":"No kinship path found between $fromId and $toId"}"""
                    ).status(Status.NotFound))
                    case Some(result) =>
                      val resp = KinshipResponse(
                        fromPersonId = fromId,
                        toPersonId   = toId,
                        chain        = result.chain.map(_.toString.toLowerCase),
                        alias        = result.alias,
                        description  = result.description,
                      )
                      ZIO.succeed(Response.json(resp.asJson.noSpaces))
                  }
                )
        },
    )
