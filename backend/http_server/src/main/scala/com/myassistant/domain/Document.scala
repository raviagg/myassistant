package com.myassistant.domain

import java.time.Instant
import java.util.UUID

final case class Document(
    id:            UUID,
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceTypeId:  UUID,
    files:         io.circe.Json,
    supersedesIds: List[UUID],
    createdAt:     Instant,
)

final case class CreateDocument(
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceTypeId:  UUID,
    embedding:     List[Double],
    files:         io.circe.Json,
    supersedesIds: List[UUID],
)
