package com.myassistant.unit.middleware

import com.myassistant.monitoring.{Metrics, MetricsMiddleware}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.Assertion.*

object MetricsMiddlewareSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Throwable] =
    suite("MetricsMiddlewareSpec")(

      test("increments http_requests_total counter on request") {
        val testRoute = Routes(
          Method.GET / "metrics-test" -> handler(ZIO.succeed(Response.ok))
        ) @@ MetricsMiddleware.instrument

        val path = "/metrics-test"

        for
          before   <- ZIO.succeed(
                        Metrics.httpRequestsTotal.labels("GET", path).get()
                      )
          _        <- testRoute.runZIO(
                        Request.get(
                          URL.decode(s"http://localhost$path").fold(_ => URL.empty, identity)
                        )
                      )
          after    <- ZIO.succeed(
                        Metrics.httpRequestsTotal.labels("GET", path).get()
                      )
        yield assertTrue(after == before + 1.0)
      },

      test("counter increments once per request") {
        val path = "/metrics-count"
        val testRoute = Routes(
          Method.GET / "metrics-count" -> handler(ZIO.succeed(Response.ok))
        ) @@ MetricsMiddleware.instrument

        val url = URL.decode(s"http://localhost$path").fold(_ => URL.empty, identity)

        for
          baseline <- ZIO.succeed(Metrics.httpRequestsTotal.labels("GET", path).get())
          _        <- testRoute.runZIO(Request.get(url))
          _        <- testRoute.runZIO(Request.get(url))
          _        <- testRoute.runZIO(Request.get(url))
          afterThree <- ZIO.succeed(Metrics.httpRequestsTotal.labels("GET", path).get())
        yield assertTrue(afterThree == baseline + 3.0)
      },

    )
