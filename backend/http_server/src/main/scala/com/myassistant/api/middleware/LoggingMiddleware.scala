package com.myassistant.api.middleware

import zio.*
import zio.http.*

/** HTTP middleware that logs incoming requests and outgoing responses.
 *
 *  Logs method, path, status code, and elapsed time using ZIO structured logging.
 *  Composed onto routes with the `@@` operator.
 */
object LoggingMiddleware:

  /** HandlerAspect that logs each HTTP request with method, path, and status. */
  val logRequests: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler {
      Handler.fromFunctionZIO[Request] { req =>
        ZIO.logInfo(s"${req.method} ${req.url.path}").as((req, ()))
      }
    }
