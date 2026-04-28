package com.myassistant.api.models

import com.myassistant.domain.{EntityTypeSchema, ProposeEntityTypeSchema}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /schemas (propose new entity type). */
final case class ProposeSchemaRequest(
    domain:            String,
    entityType:        String,
    description:       String,
    fieldDefinitions:  Json,
    extractionPrompt:  String,
    changeDescription: Option[String],
) derives Codec.AsObject:

  /** Convert to domain ProposeEntityTypeSchema. */
  def toDomain: ProposeEntityTypeSchema =
    ProposeEntityTypeSchema(
      domain            = domain,
      entityType        = entityType,
      description       = description,
      fieldDefinitions  = fieldDefinitions,
      extractionPrompt  = extractionPrompt,
      changeDescription = changeDescription,
    )

/** HTTP response body for a single schema version. */
final case class SchemaResponse(
    id:                UUID,
    domain:            String,
    entityType:        String,
    schemaVersion:     Int,
    description:       String,
    fieldDefinitions:  Json,
    mandatoryFields:   List[String],
    extractionPrompt:  String,
    isActive:          Boolean,
    changeDescription: Option[String],
    createdAt:         Instant,
) derives Codec.AsObject

object SchemaResponse:
  /** Build a SchemaResponse from the domain EntityTypeSchema. */
  def fromDomain(s: EntityTypeSchema): SchemaResponse =
    SchemaResponse(
      id                = s.id,
      domain            = s.domain,
      entityType        = s.entityType,
      schemaVersion     = s.schemaVersion,
      description       = s.description,
      fieldDefinitions  = s.fieldDefinitions,
      mandatoryFields   = s.mandatoryFields,
      extractionPrompt  = s.extractionPrompt,
      isActive          = s.isActive,
      changeDescription = s.changeDescription,
      createdAt         = s.createdAt,
    )
