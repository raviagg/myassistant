package com.myassistant.api.middleware

import com.myassistant.errors.AppError
import zio.http.*

/** Converts AppError values to well-formed JSON HTTP responses.
 *
 *  Each AppError subtype maps to a specific HTTP status code and JSON body.
 *  Route handlers call `appErrorToResponse` in their `foldZIO` error branches.
 */
object ErrorMiddleware:

  /** Convert an AppError to a JSON Response with the appropriate HTTP status code. */
  def appErrorToResponse(err: AppError): Response =
    import AppError.*
    val (status, errorCode, message, details) = err match
      case NotFound(resource, id) =>
        (Status.NotFound, "not_found", s"$resource '$id' not found", None)
      case Conflict(msg) =>
        (Status.Conflict, "conflict", msg, None)
      case ValidationError(msg) =>
        (Status.UnprocessableEntity, "validation_error", msg, None)
      case ReferentialIntegrityError(msg, blocking) =>
        (Status.Conflict, "referenced", msg,
          Some(blocking.map((k, v) => s""""$k":"$v"""").mkString("{", ",", "}")))
      case DatabaseError(_) =>
        (Status.InternalServerError, "database_error", "A database error occurred", None)
      case FileSystemError(_) =>
        (Status.InternalServerError, "filesystem_error", "A file system error occurred", None)
      case AuthError =>
        (Status.Unauthorized, "unauthorized", "Authentication required", None)
      case InternalError(_) =>
        (Status.InternalServerError, "internal_error", "An unexpected error occurred", None)

    val body = details match
      case Some(d) =>
        s"""{"error":"$errorCode","message":"$message","details":$d}"""
      case None =>
        s"""{"error":"$errorCode","message":"$message"}"""

    Response.json(body).status(status)
