package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{AuditLogResponse, CreateAuditLogRequest}
import com.myassistant.domain.{AuditLog, InteractionStatus}
import com.myassistant.services.AuditService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID

object AuditRoutes:

  private def parseStatus(s: String): Either[String, InteractionStatus] =
    s.toLowerCase match
      case "success" => Right(InteractionStatus.Success)
      case "partial" => Right(InteractionStatus.Partial)
      case "error"   => Right(InteractionStatus.Failed)
      case "failed"  => Right(InteractionStatus.Failed)
      case other     => Left(s"Unknown status: '$other'. Valid values: success, partial, error")

  private def toDomain(req: CreateAuditLogRequest): Either[String, AuditLog] =
    parseStatus(req.status).map { status =>
      AuditLog(
        id            = UUID.randomUUID(),
        personId      = req.personId,
        jobType       = req.jobType,
        messageText   = req.messageText,
        responseText  = req.responseText,
        toolCallsJson = req.toolCallsJson,
        status        = status,
        errorMessage  = req.errorMessage,
        createdAt     = java.time.Instant.now(),
      )
    }

  private def fromDomain(a: AuditLog): AuditLogResponse =
    AuditLogResponse(
      id            = a.id,
      personId      = a.personId,
      jobType       = a.jobType,
      messageText   = a.messageText,
      responseText  = a.responseText,
      toolCallsJson = a.toolCallsJson,
      status        = a.status match
        case InteractionStatus.Success => "success"
        case InteractionStatus.Partial => "partial"
        case InteractionStatus.Failed  => "error",
      errorMessage  = a.errorMessage,
      createdAt     = a.createdAt,
    )

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
                        err      => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                        logEntry => ZIO.succeed(Response.json(fromDomain(logEntry).asJson.noSpaces).status(Status.Created)),
                      )
          yield response
        },
    )
