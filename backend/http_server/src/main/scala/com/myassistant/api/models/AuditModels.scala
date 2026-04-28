package com.myassistant.api.models

import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

final case class CreateAuditLogRequest(
    personId:      Option[UUID],
    jobType:       Option[String],
    messageText:   String,
    responseText:  String,
    toolCallsJson: Option[Json],
    status:        String,
    errorMessage:  Option[String],
) derives Codec.AsObject

final case class AuditLogResponse(
    id:            UUID,
    personId:      Option[UUID],
    jobType:       Option[String],
    messageText:   String,
    responseText:  String,
    toolCallsJson: Option[Json],
    status:        String,
    errorMessage:  Option[String],
    createdAt:     Instant,
) derives Codec.AsObject
