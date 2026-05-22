package com.myassistant.domain

import io.circe.Json
import java.time.Instant
import java.util.UUID

final case class UnifiedSchema(
    id:               UUID,
    personId:         Option[UUID],
    householdId:      Option[UUID],
    name:             String,
    description:      Option[String],
    status:           String,
    fieldDefinitions: Json,
    createdAt:        Instant,
    updatedAt:        Instant,
)

final case class CreateUnifiedSchema(
    personId:         Option[UUID],
    householdId:      Option[UUID],
    name:             String,
    description:      Option[String],
    status:           String,
    fieldDefinitions: Json,
)

final case class PatchUnifiedSchema(
    name:             Option[String],
    description:      Option[String],
    status:           Option[String],
    fieldDefinitions: Option[Json],
)
