package com.myassistant.api.middleware

import zio.http.*

/** HTTP middleware that enforces bearer token authentication.
 *
 *  Reads the expected token at construction time and rejects requests
 *  that do not present a matching `Authorization: Bearer <token>` header.
 *  Uses ZIO HTTP 3.x `HandlerAspect.customAuth` so it composes cleanly
 *  with `Routes @@ authMiddleware`.
 */
object AuthMiddleware:

  /** Extract the raw bearer token value from the Authorization header, if present. */
  private def extractToken(request: Request): Option[String] =
    request.header(Header.Authorization).flatMap { header =>
      val value = header.renderedValue
      if value.startsWith("Bearer ") then Some(value.stripPrefix("Bearer ").trim)
      else None
    }

  /** Build a `HandlerAspect` that rejects requests whose bearer token does not match `expectedToken`.
   *
   *  Returns HTTP 401 with a WWW-Authenticate header for missing or invalid tokens.
   */
  def apply(expectedToken: String): HandlerAspect[Any, Unit] =
    HandlerAspect.customAuth(
      request => extractToken(request).exists(_ == expectedToken),
    )
