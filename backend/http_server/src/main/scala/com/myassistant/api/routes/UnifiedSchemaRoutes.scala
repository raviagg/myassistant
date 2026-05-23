package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.*
import com.myassistant.services.UnifiedSchemaService
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

object UnifiedSchemaRoutes:

  val routes: Routes[UnifiedSchemaService & ZConnectionPool, Nothing] =
    Routes(

      // GET /api/v1/unified-schemas/source-schemas/sample?sourceType=&tableName=&sourceConnectionId=&personId=&householdId=&limit=
      // IMPORTANT: registered before /source-schemas and /{id}
      Method.GET / "api" / "v1" / "unified-schemas" / "source-schemas" / "sample" ->
        handler { (req: Request) =>
          val sourceType         = req.queryParam("sourceType").getOrElse("")
          val tableName          = req.queryParam("tableName").getOrElse("")
          val sourceConnectionId = req.queryParam("sourceConnectionId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val personId           = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId        = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val limit              = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(5).min(20)
          if sourceType.isEmpty || tableName.isEmpty then
            ZIO.succeed(Response.json("""{"error":"bad_request","message":"sourceType and tableName are required"}""").status(Status.BadRequest))
          else
            ZIO.serviceWithZIO[UnifiedSchemaService](_.sampleRows(sourceType, tableName, sourceConnectionId, personId, householdId, limit))
              .foldZIO(
                err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                rows => ZIO.succeed(Response.json(SampleRowsResponse(rows).asJson.noSpaces)),
              )
        },

      // GET /api/v1/unified-schemas/source-schemas?personId=&householdId=
      // IMPORTANT: registered before /{id} to prevent routing conflict
      Method.GET / "api" / "v1" / "unified-schemas" / "source-schemas" ->
        handler { (req: Request) =>
          val personId    = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          ZIO.serviceWithZIO[UnifiedSchemaService](_.sourceSchemas(personId, householdId))
            .foldZIO(
              err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              resp => ZIO.succeed(Response.json(resp.asJson.noSpaces)),
            )
        },

      // GET /api/v1/unified-schemas?personId=&householdId=
      Method.GET / "api" / "v1" / "unified-schemas" ->
        handler { (req: Request) =>
          val personId    = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          ZIO.serviceWithZIO[UnifiedSchemaService](_.list(personId, householdId))
            .foldZIO(
              err     => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              schemas => ZIO.succeed(Response.json(
                io.circe.Json.obj("items" -> io.circe.Json.arr(schemas.map(UnifiedSchemaResponse.fromDomain(_).asJson)*)).noSpaces
              )),
            )
        },

      // POST /api/v1/unified-schemas
      Method.POST / "api" / "v1" / "unified-schemas" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateUnifiedSchemaRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                ZIO.serviceWithZIO[UnifiedSchemaService](_.create(createReq.toDomain))
                  .foldZIO(
                    err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    schema => ZIO.succeed(Response.json(UnifiedSchemaResponse.fromDomain(schema).asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      // GET /api/v1/unified-schemas/{id}
      Method.GET / "api" / "v1" / "unified-schemas" / string("id") ->
        handler { (idStr: String, _: Request) =>
          Try(UUID.fromString(idStr)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $idStr"}""").status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[UnifiedSchemaService](_.get(id))
                .foldZIO(
                  err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  schema => ZIO.succeed(Response.json(UnifiedSchemaResponse.fromDomain(schema).asJson.noSpaces)),
                )
        },

      // PATCH /api/v1/unified-schemas/{id}
      Method.PATCH / "api" / "v1" / "unified-schemas" / string("id") ->
        handler { (idStr: String, req: Request) =>
          Try(UUID.fromString(idStr)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $idStr"}""").status(Status.BadRequest))
            case Right(id) =>
              for
                bodyStr  <- req.body.asString.orDie
                response <- decode[PatchUnifiedSchemaRequest](bodyStr) match
                  case Left(err) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                    ).status(Status.BadRequest))
                  case Right(patchReq) =>
                    ZIO.serviceWithZIO[UnifiedSchemaService](_.patch(id, patchReq.toDomain))
                      .foldZIO(
                        err    => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        schema => ZIO.succeed(Response.json(UnifiedSchemaResponse.fromDomain(schema).asJson.noSpaces)),
                      )
              yield response
        },

      // DELETE /api/v1/unified-schemas/{id}
      Method.DELETE / "api" / "v1" / "unified-schemas" / string("id") ->
        handler { (idStr: String, _: Request) =>
          Try(UUID.fromString(idStr)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $idStr"}""").status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[UnifiedSchemaService](_.delete(id))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },

      // GET /api/v1/unified-schemas/{id}/data?limit=&offset=
      Method.GET / "api" / "v1" / "unified-schemas" / string("id") / "data" ->
        handler { (idStr: String, req: Request) =>
          Try(UUID.fromString(idStr)).toEither match
            case Left(_) =>
              ZIO.succeed(Response.json(s"""{"error":"bad_request","message":"Invalid UUID: $idStr"}""").status(Status.BadRequest))
            case Right(id) =>
              val limit  = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50).min(200)
              val offset = req.queryParam("offset").flatMap(_.toIntOption).getOrElse(0).max(0)
              ZIO.serviceWithZIO[UnifiedSchemaService](_.data(id, limit, offset))
                .foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  resp => ZIO.succeed(Response.json(resp.asJson.noSpaces)),
                )
        },
    )
