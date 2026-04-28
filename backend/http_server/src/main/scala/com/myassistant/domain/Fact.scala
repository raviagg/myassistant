package com.myassistant.domain

import java.time.Instant
import java.util.UUID

enum OperationType:
  case Create, Update, Delete

final case class Fact(
    id:               UUID,
    documentId:       UUID,
    schemaId:         UUID,
    entityInstanceId: UUID,
    operationType:    OperationType,
    fields:           io.circe.Json,
    createdAt:        Instant,
)

final case class CreateFact(
    documentId:       UUID,
    schemaId:         UUID,
    entityInstanceId: UUID,
    operationType:    OperationType,
    fields:           io.circe.Json,
    embedding:        List[Double],
)

final case class CurrentFact(
    entityInstanceId: UUID,
    schemaId:         UUID,
    personId:         Option[UUID],
    householdId:      Option[UUID],
    fields:           io.circe.Json,
    lastUpdatedAt:    Instant,
)
