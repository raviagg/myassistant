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
 */
object LoggingMiddleware:

  private type State = (Long, String)  // (startMs, "METHOD /path")

  val logRequests: HandlerAspect[Any, Unit] =
    HandlerAspect.InterceptPatchZIO { (req: Request) =>
      Clock.currentTime(TimeUnit.MILLISECONDS)
        .map(start => (start, s"${req.method} ${req.url.path}"))
    } { (resp: Response, state: State) =>
      val (start, label) = state
      Clock.currentTime(TimeUnit.MILLISECONDS).flatMap { end =>
        ZIO.logInfo(s"$label → ${resp.status.code} (${end - start}ms)")
          .as(Response.Patch.empty)
      }
    }
