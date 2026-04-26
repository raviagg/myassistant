package com.myassistant.api.models

import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /audit/interactions. */
final case class CreateAuditLogRequest(
    personId:  Option[UUID],
    jobType:   Option[String],
    message:   String,
    response:  Option[String],
    toolCalls: Json,
    status:    String,
    error:     Option[String],
) derives Codec.AsObject

/** HTTP response body for a single audit log entry. */
final case class AuditLogResponse(
    id:        UUID,
    personId:  Option[UUID],
    jobType:   Option[String],
    message:   String,
    response:  Option[String],
    toolCalls: Json,
    status:    String,
    error:     Option[String],
    createdAt: Instant,
) derives Codec.AsObject
