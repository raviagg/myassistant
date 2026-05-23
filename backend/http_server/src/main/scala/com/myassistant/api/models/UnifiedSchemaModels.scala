package com.myassistant.api.models

import com.myassistant.domain.{CreateUnifiedSchema, PatchUnifiedSchema, UnifiedSchema}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

final case class CreateUnifiedSchemaRequest(
    personId:         Option[UUID],
    householdId:      Option[UUID],
    name:             String,
    description:      Option[String],
    status:           Option[String],
    fieldDefinitions: Json,
) derives Codec.AsObject:

  def toDomain: CreateUnifiedSchema =
    CreateUnifiedSchema(
      personId         = personId,
      householdId      = householdId,
      name             = name,
      description      = description,
      status           = status.getOrElse("proposed"),
      fieldDefinitions = fieldDefinitions,
    )

final case class PatchUnifiedSchemaRequest(
    name:             Option[String],
    description:      Option[String],
    status:           Option[String],
    fieldDefinitions: Option[Json],
) derives Codec.AsObject:

  def toDomain: PatchUnifiedSchema =
    PatchUnifiedSchema(
      name             = name,
      description      = description,
      status           = status,
      fieldDefinitions = fieldDefinitions,
    )

final case class UnifiedSchemaResponse(
    id:               UUID,
    personId:         Option[UUID],
    householdId:      Option[UUID],
    name:             String,
    description:      Option[String],
    status:           String,
    fieldDefinitions: Json,
    createdAt:        Instant,
    updatedAt:        Instant,
) derives Codec.AsObject

object UnifiedSchemaResponse:
  def fromDomain(u: UnifiedSchema): UnifiedSchemaResponse =
    UnifiedSchemaResponse(
      id               = u.id,
      personId         = u.personId,
      householdId      = u.householdId,
      name             = u.name,
      description      = u.description,
      status           = u.status,
      fieldDefinitions = u.fieldDefinitions,
      createdAt        = u.createdAt,
      updatedAt        = u.updatedAt,
    )

// Source schema types for /source-schemas endpoint

final case class SourceColumnResponse(
    name:     String,
    dataType: String,
) derives Codec.AsObject

final case class ForeignKeyResponse(
    column:    String,
    refTable:  String,
    refColumn: String,
) derives Codec.AsObject

final case class SourceTableResponse(
    tableName:   String,
    columns:     List[SourceColumnResponse],
    foreignKeys: List[ForeignKeyResponse],
) derives Codec.AsObject

final case class SourceGroupResponse(
    sourceConnectionId:   Option[UUID],
    sourceType:           String,
    connectionName:       String,
    tables:               List[SourceTableResponse],
) derives Codec.AsObject

final case class SourceSchemasResponse(
    profile: SourceGroupResponse,
    sources: List[SourceGroupResponse],
) derives Codec.AsObject

// Sample rows from /source-schemas/sample endpoint

final case class SampleRowsResponse(
    rows: List[Json],
) derives Codec.AsObject

// Data row from /data endpoint

final case class UnifiedDataRow(
    sourceConnectionId: Option[UUID],
    sourceType:         String,
    fields:             Map[String, Json],
) derives Codec.AsObject

final case class UnifiedDataResponse(
    items:  List[UnifiedDataRow],
    total:  Int,
    limit:  Int,
    offset: Int,
) derives Codec.AsObject
