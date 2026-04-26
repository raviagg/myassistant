package com.myassistant.monitoring

import zio.*
import zio.http.*

/** ZIO HTTP middleware that records Prometheus request metrics.
 *
 *  Uses `HandlerAspect.interceptHandler` to capture both the incoming request
 *  (for method/path labels) and the outgoing response (for status label) and
 *  increments the `http_requests_total` Prometheus counter.
 */
object MetricsMiddleware:

  /** HandlerAspect that increments the Prometheus http_requests_total counter.
   *
   *  Passes `(method, path)` as state from the incoming handler so the outgoing
   *  handler can label the counter with all three dimensions.
   */
  val instrument: HandlerAspect[Any, (String, String)] =
    HandlerAspect.interceptHandler(
      Handler.fromFunctionZIO[Request] { req =>
        val method = req.method.name
        val path   = req.path.encode
        ZIO.succeed((req, (method, path)))
      },
      Handler.fromFunctionZIO[((String, String), Response)] { case ((method, path), res) =>
        ZIO.succeed {
          Metrics.httpRequestsTotal
            .labels(method, path, res.status.code.toString)
            .inc()
          res
        }
      },
    )
