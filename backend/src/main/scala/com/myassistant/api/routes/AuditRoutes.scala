package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{AuditLogResponse, CreateAuditLogRequest}
import com.myassistant.domain.{AuditLog, InteractionStatus}
import com.myassistant.errors.AppError
import com.myassistant.services.AuditService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID

/** HTTP routes for audit log interactions.
 *
 *  POST   /api/v1/audit/interactions   — log a new interaction
 */
object AuditRoutes:

  /** Parse a lowercase interaction status string to a domain InteractionStatus. */
  private def parseStatus(s: String): Either[String, InteractionStatus] =
    s.toLowerCase match
      case "success" => Right(InteractionStatus.Success)
      case "partial" => Right(InteractionStatus.Partial)
      case "failed"  => Right(InteractionStatus.Failed)
      case other     => Left(s"Unknown interaction status: '$other'. Valid values: success, partial, failed")

  /** Convert a CreateAuditLogRequest to a domain AuditLog. */
  private def toDomain(req: CreateAuditLogRequest): Either[String, AuditLog] =
    parseStatus(req.status).map { status =>
      AuditLog(
        id        = UUID.randomUUID(),
        personId  = req.personId,
        jobType   = req.jobType,
        message   = req.message,
        response  = req.response,
        toolCalls = req.toolCalls,
        status    = status,
        error     = req.error,
        createdAt = java.time.Instant.now(),
      )
    }

  /** Convert a domain AuditLog to an AuditLogResponse. */
  private def fromDomain(a: AuditLog): AuditLogResponse =
    AuditLogResponse(
      id        = a.id,
      personId  = a.personId,
      jobType   = a.jobType,
      message   = a.message,
      response  = a.response,
      toolCalls = a.toolCalls,
      status    = a.status.toString.toLowerCase,
      error     = a.error,
      createdAt = a.createdAt,
    )

  /** Build audit log routes requiring AuditService and ZConnectionPool in the environment. */
  val routes: Routes[AuditService & ZConnectionPool, Nothing] =
    Routes(
      Method.POST / "api" / "v1" / "audit" / "interactions" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateAuditLogRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                toDomain(createReq) match
                  case Left(msg) =>
                    ZIO.succeed(Response.json(
                      s"""{"error":"validation_error","message":"$msg"}"""
                    ).status(Status.UnprocessableEntity))
                  case Right(entry) =>
                    ZIO.serviceWithZIO[AuditService](_.log(entry))
                      .foldZIO(
                        err       => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        logEntry  => ZIO.succeed(Response.json(fromDomain(logEntry).asJson.noSpaces).status(Status.Created)),
                      )
          yield response
        },
    )
