package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateEntityTypeSchemaRequest, PagedResponse, SchemaResponse, UpdateEntityTypeSchemaRequest}
import com.myassistant.services.SchemaService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

object SchemaRoutes:

  val routes: Routes[SchemaService & ZConnectionPool, Nothing] =
    Routes(
      // GET /api/v1/schemas?domainId=...&entityType=...&activeOnly=...
      Method.GET / "api" / "v1" / "schemas" ->
        handler { (req: Request) =>
          val domainId   = req.queryParam("domainId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val entityType = req.queryParam("entityType")
          val activeOnly = req.queryParam("activeOnly").map(_.toLowerCase != "false").getOrElse(true)
          ZIO.serviceWithZIO[SchemaService](_.listSchemas(domainId, entityType, activeOnly))
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              schemas =>
                ZIO.succeed(Response.json(
                  io.circe.Json.obj("items" -> io.circe.Json.arr(schemas.map(SchemaResponse.fromDomain(_).asJson)*)).noSpaces
                ))
            )
        },

      // POST /api/v1/schemas — create a new schema
      Method.POST / "api" / "v1" / "schemas" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateEntityTypeSchemaRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                ZIO.serviceWithZIO[SchemaService](_.createSchema(createReq.toDomain))
                  .foldZIO(
                    err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      // GET /api/v1/schemas/current?domainId=...&entityType=...
      Method.GET / "api" / "v1" / "schemas" / "current" ->
        handler { (req: Request) =>
          (req.queryParam("domainId"), req.queryParam("entityType")) match
            case (None, _) =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'domainId' is required"}"""
              ).status(Status.BadRequest))
            case (_, None) =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'entityType' is required"}"""
              ).status(Status.BadRequest))
            case (Some(domainIdStr), Some(entityType)) =>
              Try(UUID.fromString(domainIdStr)).toEither match
                case Left(_) =>
                  ZIO.succeed(Response.json(
                    s"""{"error":"bad_request","message":"Invalid UUID for domainId: $domainIdStr"}"""
                  ).status(Status.BadRequest))
                case Right(domainId) =>
                  ZIO.serviceWithZIO[SchemaService](_.getCurrentSchema(domainId, entityType))
                    .foldZIO(
                      err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                      schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces)),
                    )
        },

      // GET /api/v1/schemas/:schemaId
      Method.GET / "api" / "v1" / "schemas" / string("schemaId") ->
        handler { (schemaId: String, _: Request) =>
          Try(UUID.fromString(schemaId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $schemaId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[SchemaService](_.getSchema(id))
                .foldZIO(
                  err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces)),
                )
        },

      // POST /api/v1/schemas/:domainId/:entityType/versions — add a new schema version
      Method.POST / "api" / "v1" / "schemas" / string("domainId") / string("entityType") / "versions" ->
        handler { (domainIdStr: String, entityType: String, req: Request) =>
          Try(UUID.fromString(domainIdStr)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID for domainId: $domainIdStr"}"""
              ).status(Status.BadRequest))
            case Right(domainId) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[UpdateEntityTypeSchemaRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(updateReq) =>
                    ZIO.serviceWithZIO[SchemaService](_.addVersion(domainId, entityType, updateReq.toDomain))
                      .foldZIO(
                        err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces).status(Status.Created)),
                      )
              yield response
        },

      // DELETE /api/v1/schemas/:domainId/:entityType/active — deactivate active schema
      Method.DELETE / "api" / "v1" / "schemas" / string("domainId") / string("entityType") / "active" ->
        handler { (domainIdStr: String, entityType: String, _: Request) =>
          Try(UUID.fromString(domainIdStr)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID for domainId: $domainIdStr"}"""
              ).status(Status.BadRequest))
            case Right(domainId) =>
              ZIO.serviceWithZIO[SchemaService](_.deactivateSchema(domainId, entityType))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },
    )
