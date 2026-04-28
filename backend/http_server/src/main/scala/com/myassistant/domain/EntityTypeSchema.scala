package com.myassistant.domain

import java.time.Instant
import java.util.UUID

final case class EntityTypeSchema(
    id:               UUID,
    domainId:         UUID,
    entityType:       String,
    schemaVersion:    Int,
    description:      Option[String],
    fieldDefinitions: io.circe.Json,
    mandatoryFields:  List[String],
    isActive:         Boolean,
    createdAt:        Instant,
    updatedAt:        Instant,
)

final case class CreateEntityTypeSchema(
    domainId:         UUID,
    entityType:       String,
    fieldDefinitions: io.circe.Json,
    description:      Option[String],
)

final case class CreateSchemaVersion(
    fieldDefinitions: io.circe.Json,
    description:      Option[String],
)
