package com.myassistant.api.models

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** Envelope for paginated list responses.
 *
 *  Wraps a list payload with total count and pagination cursors.
 */
final case class PagedResponse[A](
    items:  List[A],
    total:  Long,
    limit:  Int,
    offset: Int,
) derives Codec.AsObject

/** Standard error response body returned on all 4xx and 5xx responses. */
final case class ErrorResponse(
    error:   String,
    message: String,
    details: Option[Map[String, String]],
) derives Codec.AsObject

/** Minimal health check response. */
final case class HealthResponse(
    status:  String,
    version: String,
) derives Codec.AsObject
