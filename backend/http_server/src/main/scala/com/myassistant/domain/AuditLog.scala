package com.myassistant.domain

import java.time.Instant
import java.util.UUID

enum InteractionStatus:
  case Success, Partial, Failed

final case class AuditLog(
    id:            UUID,
    personId:      Option[UUID],
    jobType:       Option[String],
    messageText:   String,
    responseText:  String,
    toolCallsJson: Option[io.circe.Json],
    status:        InteractionStatus,
    errorMessage:  Option[String],
    createdAt:     Instant,
)
