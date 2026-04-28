package com.myassistant.api.models

import com.myassistant.domain.{CreateEntityTypeSchema, CreateSchemaVersion, EntityTypeSchema}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

final case class CreateEntityTypeSchemaRequest(
    domainId:         UUID,
    entityType:       String,
    fieldDefinitions: Json,
    description:      Option[String],
) derives Codec.AsObject:

  def toDomain: CreateEntityTypeSchema =
    CreateEntityTypeSchema(
      domainId         = domainId,
      entityType       = entityType,
      fieldDefinitions = fieldDefinitions,
      description      = description,
    )

final case class UpdateEntityTypeSchemaRequest(
    fieldDefinitions: Json,
    description:      Option[String],
) derives Codec.AsObject:

  def toDomain: CreateSchemaVersion =
    CreateSchemaVersion(
      fieldDefinitions = fieldDefinitions,
      description      = description,
    )

final case class SchemaResponse(
    id:               UUID,
    domainId:         UUID,
    entityType:       String,
    schemaVersion:    Int,
    isActive:         Boolean,
    description:      Option[String],
    fieldDefinitions: Json,
    mandatoryFields:  List[String],
    createdAt:        Instant,
    updatedAt:        Instant,
) derives Codec.AsObject

object SchemaResponse:
  def fromDomain(s: EntityTypeSchema): SchemaResponse =
    SchemaResponse(
      id               = s.id,
      domainId         = s.domainId,
      entityType       = s.entityType,
      schemaVersion    = s.schemaVersion,
      isActive         = s.isActive,
      description      = s.description,
      fieldDefinitions = s.fieldDefinitions,
      mandatoryFields  = s.mandatoryFields,
      createdAt        = s.createdAt,
      updatedAt        = s.updatedAt,
    )
