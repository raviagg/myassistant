package com.myassistant.monitoring

import io.prometheus.client.{Counter, Histogram, CollectorRegistry}
import io.prometheus.client.hotspot.DefaultExports
import zio.*

/** Prometheus metrics registry for the application.
 *
 *  Exposes request counters and latency histograms collected by
 *  MetricsMiddleware.  Uses the default Prometheus CollectorRegistry.
 */
object Metrics:

  /** Initialise JVM hotspot default metrics (GC, memory, threads). */
  def initHotspot(): Unit = DefaultExports.initialize()

  /** Counter tracking total HTTP requests by method, path, and status code. */
  val httpRequestsTotal: Counter =
    Counter.build()
      .name("http_requests_total")
      .help("Total number of HTTP requests")
      .labelNames("method", "path", "status")
      .register()

  /** Histogram tracking HTTP request duration in seconds. */
  val httpRequestDuration: Histogram =
    Histogram.build()
      .name("http_request_duration_seconds")
      .help("HTTP request duration in seconds")
      .labelNames("method", "path")
      .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5)
      .register()

  /** Counter tracking database query errors by repository and operation. */
  val dbErrorsTotal: Counter =
    Counter.build()
      .name("db_errors_total")
      .help("Total database errors by repository and operation")
      .labelNames("repository", "operation")
      .register()

  /** ZLayer that initialises hotspot metrics on startup. */
  val live: ZLayer[Any, Nothing, Unit] =
    ZLayer.fromZIO(ZIO.succeed(initHotspot()))
