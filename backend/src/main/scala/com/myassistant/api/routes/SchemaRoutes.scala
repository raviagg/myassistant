package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{PagedResponse, ProposeSchemaRequest, SchemaResponse}
import com.myassistant.services.SchemaService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

/** HTTP routes for entity type schema governance.
 *
 *  GET    /api/v1/schemas                                   — list current schemas (query: domain)
 *  POST   /api/v1/schemas                                   — propose a new schema version
 *  GET    /api/v1/schemas/current                           — get current schema (query: domain, entityType)
 *  GET    /api/v1/schemas/:id                               — get a schema version by id
 *  POST   /api/v1/schemas/:domainId/:entityType/versions    — evolve schema (propose new version)
 *  DELETE /api/v1/schemas/:domainId/:entityType/active      — deactivate schema by id (query: id)
 */
object SchemaRoutes:

  /** Build schema governance routes requiring SchemaService and ZConnectionPool in the environment. */
  val routes: Routes[SchemaService & ZConnectionPool, Nothing] =
    Routes(
      Method.GET / "api" / "v1" / "schemas" ->
        handler { (req: Request) =>
          val domain = req.queryParam("domain")
          ZIO.serviceWithZIO[SchemaService](_.listSchemas(domain))
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              schemas =>
                ZIO.succeed(Response.json(
                  PagedResponse(
                    items  = schemas.map(SchemaResponse.fromDomain),
                    total  = schemas.size.toLong,
                    limit  = schemas.size,
                    offset = 0,
                  ).asJson.noSpaces
                ))
            )
        },

      Method.POST / "api" / "v1" / "schemas" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[ProposeSchemaRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(proposeReq) =>
                ZIO.serviceWithZIO[SchemaService](_.proposeSchema(proposeReq.toDomain))
                  .foldZIO(
                    err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      // GET /api/v1/schemas/current?domain=...&entityType=...
      Method.GET / "api" / "v1" / "schemas" / "current" ->
        handler { (req: Request) =>
          (req.queryParam("domain"), req.queryParam("entityType")) match
            case (None, _) =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'domain' is required"}"""
              ).status(Status.BadRequest))
            case (_, None) =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'entityType' is required"}"""
              ).status(Status.BadRequest))
            case (Some(domain), Some(entityType)) =>
              ZIO.serviceWithZIO[SchemaService](_.getCurrentSchema(domain, entityType))
                .foldZIO(
                  err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces)),
                )
        },

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

      // POST /api/v1/schemas/:domain/:entityType/versions — evolve schema (propose new version)
      Method.POST / "api" / "v1" / "schemas" / string("domain") / string("entityType") / "versions" ->
        handler { (domain: String, entityType: String, req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[ProposeSchemaRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(proposeReq) =>
                // Use domain and entityType from the path, override any values in the body
                import com.myassistant.domain.ProposeEntityTypeSchema
                val domainReq = ProposeEntityTypeSchema(
                  domain            = domain,
                  entityType        = entityType,
                  description       = proposeReq.description,
                  fieldDefinitions  = proposeReq.fieldDefinitions,
                  extractionPrompt  = proposeReq.extractionPrompt,
                  changeDescription = proposeReq.changeDescription,
                )
                ZIO.serviceWithZIO[SchemaService](_.proposeSchema(domainReq))
                  .foldZIO(
                    err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    schema => ZIO.succeed(Response.json(SchemaResponse.fromDomain(schema).asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      // DELETE /api/v1/schemas/:domain/:entityType/active?id=<uuid> — deactivate a schema version
      Method.DELETE / "api" / "v1" / "schemas" / string("domain") / string("entityType") / "active" ->
        handler { (domain: String, entityType: String, req: Request) =>
          req.queryParam("id") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'id' is required"}"""
              ).status(Status.BadRequest))
            case Some(idStr) =>
              Try(UUID.fromString(idStr)).toEither match
                case Left(_)   =>
                  ZIO.succeed(Response.json(
                    s"""{"error":"bad_request","message":"Invalid UUID: $idStr"}"""
                  ).status(Status.BadRequest))
                case Right(id) =>
                  ZIO.serviceWithZIO[SchemaService](_.deactivateSchema(id))
                    .foldZIO(
                      err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                      _   => ZIO.succeed(Response.status(Status.NoContent)),
                    )
        },
    )
