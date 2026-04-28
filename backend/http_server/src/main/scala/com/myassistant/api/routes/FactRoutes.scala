package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateFactRequest, FactResponse, PagedResponse, SearchFactsRequest}
import com.myassistant.services.FactService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for the append-only fact operation stream.
 *
 *  POST   /api/v1/facts                              — append a fact row
 *  GET    /api/v1/facts/current                      — list facts by schema (query: schemaId, limit, offset)
 *  GET    /api/v1/facts/:entityId/current            — get a fact row by id
 *  GET    /api/v1/facts/:entityId/history            — get history for an entity instance
 *  POST   /api/v1/facts/search                       — search facts by document (body: documentId)
 */
object FactRoutes:

  /** Build fact routes requiring FactService and ZConnectionPool in the environment. */
  val routes: Routes[FactService & ZConnectionPool, Nothing] =
    Routes(
      Method.POST / "api" / "v1" / "facts" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateFactRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                createReq.toDomain match
                  case Left(msg) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"validation_error","message":"$msg"}"""
                    ).status(Status.UnprocessableEntity))
                  case Right(domainReq) =>
                    ZIO.serviceWithZIO[FactService](_.createFact(domainReq))
                      .foldZIO(
                        err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        fact => ZIO.succeed(Response.json(FactResponse.fromDomain(fact).asJson.noSpaces).status(Status.Created)),
                      )
          yield response
        },

      // GET /api/v1/facts/current — list facts by schema with pagination
      Method.GET / "api" / "v1" / "facts" / "current" ->
        handler { (req: Request) =>
          req.queryParam("schemaId") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'schemaId' is required"}"""
              ).status(Status.BadRequest))
            case Some(schemaIdStr) =>
              Try(UUID.fromString(schemaIdStr)).toEither match
                case Left(_)       =>
                  ZIO.succeed(Response.json(
                    s"""{"error":"bad_request","message":"Invalid UUID: $schemaIdStr"}"""
                  ).status(Status.BadRequest))
                case Right(schemaId) =>
                  val limit  = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
                  val offset = req.queryParam("offset").flatMap(_.toIntOption).getOrElse(0)
                  ZIO.serviceWithZIO[FactService](_.getFactsBySchema(schemaId, limit, offset))
                    .foldZIO(
                      err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                      facts =>
                        ZIO.succeed(Response.json(
                          PagedResponse(
                            items  = facts.map(FactResponse.fromDomain),
                            total  = facts.size.toLong,
                            limit  = limit,
                            offset = offset,
                          ).asJson.noSpaces
                        ))
                    )
        },

      // GET /api/v1/facts/:entityId/current — retrieve a single fact row by id
      Method.GET / "api" / "v1" / "facts" / string("entityId") / "current" ->
        handler { (entityId: String, _: Request) =>
          Try(UUID.fromString(entityId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $entityId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[FactService](_.getFact(id))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  fact => ZIO.succeed(Response.json(FactResponse.fromDomain(fact).asJson.noSpaces)),
                )
        },

      // GET /api/v1/facts/:entityId/history — full operation history for an entity instance
      Method.GET / "api" / "v1" / "facts" / string("entityId") / "history" ->
        handler { (entityId: String, _: Request) =>
          Try(UUID.fromString(entityId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $entityId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[FactService](_.getEntityHistory(id))
                .foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  facts =>
                    ZIO.succeed(Response.json(
                      PagedResponse(
                        items  = facts.map(FactResponse.fromDomain),
                        total  = facts.size.toLong,
                        limit  = facts.size,
                        offset = 0,
                      ).asJson.noSpaces
                    ))
                )
        },

      // POST /api/v1/facts/search — search facts by documentId
      Method.POST / "api" / "v1" / "facts" / "search" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[SearchFactsRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(searchReq) =>
                // SearchFactsRequest contains a `query` string; treat it as a documentId if it parses as UUID,
                // otherwise fall back to an empty list (full semantic search requires pgvector pipeline)
                Try(UUID.fromString(searchReq.query)).toOption match
                  case Some(documentId) =>
                    ZIO.serviceWithZIO[FactService](_.getFactsByDocument(documentId))
                      .foldZIO(
                        err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        facts =>
                          val limit = searchReq.limit.getOrElse(facts.size)
                          ZIO.succeed(Response.json(
                            PagedResponse(
                              items  = facts.take(limit).map(FactResponse.fromDomain),
                              total  = facts.size.toLong,
                              limit  = limit,
                              offset = 0,
                            ).asJson.noSpaces
                          ))
                      )
                  case None =>
                    ZIO.succeed(Response.json(
                      PagedResponse(
                        items  = List.empty[FactResponse],
                        total  = 0L,
                        limit  = searchReq.limit.getOrElse(20),
                        offset = 0,
                      ).asJson.noSpaces
                    ))
          yield response
        },
    )
