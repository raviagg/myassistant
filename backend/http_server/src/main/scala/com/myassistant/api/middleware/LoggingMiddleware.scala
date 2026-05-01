package com.myassistant.api.middleware

import zio.*
import zio.http.*
import java.util.concurrent.TimeUnit

/** HTTP middleware that logs every request and its response.
 *
 *  Emits one log line per request on completion:
 *    GET /api/v1/persons/uuid → 200 (34ms)
 *
 *  Applied to all routes (public + protected) in Router.
 *
 *  Uses interceptHandlerStateful (not InterceptPatchZIO) because in ZIO HTTP 3.0.1
 *  the InterceptPatchZIO path silently drops the response for HTTP PATCH requests.
 */
object LoggingMiddleware:

  private type State = (Long, String)  // (startMs, "METHOD /path")

  val logRequests: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptHandlerStateful(
      Handler.fromFunctionZIO { (req: Request) =>
        Clock.currentTime(TimeUnit.MILLISECONDS)
          .map { start =>
            val state: State = (start, s"${req.method} ${req.url.path}")
            (state, (req, ()))
          }
      }
    )(
      Handler.fromFunctionZIO { (stateAndResp: (State, Response)) =>
        val ((start, label), resp) = stateAndResp
        Clock.currentTime(TimeUnit.MILLISECONDS).flatMap { end =>
          ZIO.logInfo(s"$label → ${resp.status.code} (${end - start}ms)")
            .as(resp)
        }
      }
    )
