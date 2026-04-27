package com.myassistant.monitoring

import zio.*
import zio.http.*

/** ZIO HTTP middleware that records Prometheus request metrics.
 *
 *  Uses `HandlerAspect.interceptIncomingHandler` to capture the incoming request
 *  and increments the `http_requests_total` Prometheus counter.
 *  ZIO HTTP 3.0.1 does not support stateful two-phase intercept, so response
 *  status is not available as a label here.
 */
object MetricsMiddleware:

  /** HandlerAspect that increments the Prometheus http_requests_total counter. */
  val instrument: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler {
      Handler.fromFunctionZIO[Request] { req =>
        ZIO.succeed {
          Metrics.httpRequestsTotal
            .labels(req.method.name, req.path.encode)
            .inc()
          (req, ())
        }
      }
    }
