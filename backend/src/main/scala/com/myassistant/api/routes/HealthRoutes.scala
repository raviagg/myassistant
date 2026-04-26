package com.myassistant.api.routes

import com.myassistant.api.models.HealthResponse
import io.circe.syntax.*
import io.circe.generic.semiauto.deriveEncoder
import zio.*
import zio.http.*

/** Routes for the health check endpoint.
 *
 *  GET /health — returns 200 OK with service status and version.
 *  This endpoint is intentionally unauthenticated for load balancer probes.
 */
object HealthRoutes:

  /** Build the health check routes. */
  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "health" ->
        handler {
          Response.json("""{"status":"ok","version":"0.1.0"}""")
        },
    )
