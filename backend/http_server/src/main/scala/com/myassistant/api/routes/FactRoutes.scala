package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{
  CreateFactRequest, CurrentFactResponse, FactHistoryResponse,
  FactResponse, PagedResponse, SearchCurrentFactsRequest
}
import com.myassistant.services.FactService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

object FactRoutes:

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

      // GET /api/v1/facts/current — list current entity states
      Method.GET / "api" / "v1" / "facts" / "current" ->
        handler { (req: Request) =>
          val personId    = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val domainId    = req.queryParam("domainId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val entityType  = req.queryParam("entityType")
          val limit       = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
          val offset      = req.queryParam("offset").flatMap(_.toIntOption).getOrElse(0)
          ZIO.serviceWithZIO[FactService](_.listCurrentFacts(personId, householdId, domainId, entityType, limit, offset))
            .foldZIO(
              err             => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              (items, total)  =>
                ZIO.succeed(Response.json(
                  PagedResponse(
                    items  = items.map(CurrentFactResponse.fromDomain),
                    total  = total,
                    limit  = limit,
                    offset = offset,
                  ).asJson.noSpaces
                ))
            )
        },

      // GET /api/v1/facts/:entityId/current — merged current state for one entity instance
      Method.GET / "api" / "v1" / "facts" / string("entityId") / "current" ->
        handler { (entityId: String, _: Request) =>
          Try(UUID.fromString(entityId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $entityId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[FactService](_.getCurrentFact(id))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  cf  => ZIO.succeed(Response.json(CurrentFactResponse.fromDomain(cf).asJson.noSpaces)),
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
                      FactHistoryResponse(
                        entityInstanceId = id,
                        items            = facts.map(FactResponse.fromDomain),
                      ).asJson.noSpaces
                    ))
                )
        },

      // POST /api/v1/facts/search — vector similarity search over current entity states
      Method.POST / "api" / "v1" / "facts" / "search" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[SearchCurrentFactsRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(searchReq) =>
                val limit     = searchReq.limit.getOrElse(10)
                val threshold = searchReq.similarityThreshold.getOrElse(0.7)
                ZIO.serviceWithZIO[FactService](
                  _.searchCurrentFacts(searchReq.embedding, searchReq.personId, searchReq.householdId,
                    searchReq.domainId, searchReq.entityType, limit, threshold)
                ).foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  items =>
                    import io.circe.Json
                    val jsonItems = items.map { case (cf, score) =>
                      CurrentFactResponse.fromDomain(cf).asJson.deepMerge(
                        io.circe.parser.parse(s"""{"similarity_score":$score}""").getOrElse(Json.obj())
                      )
                    }
                    ZIO.succeed(Response.json(
                      io.circe.Json.obj("items" -> io.circe.Json.arr(jsonItems*)).noSpaces
                    ))
                )
          yield response
        },
    )
