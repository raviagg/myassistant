package com.myassistant.api.models

import com.myassistant.domain.{CreateFact, CurrentFact, Fact, OperationType}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

final case class CreateFactRequest(
    documentId:       UUID,
    schemaId:         UUID,
    entityInstanceId: UUID,
    operationType:    String,
    fields:           Json,
    embedding:        List[Double],
) derives Codec.AsObject:

  def toDomain: Either[String, CreateFact] =
    parseOperationType(operationType).map(op =>
      CreateFact(
        documentId       = documentId,
        schemaId         = schemaId,
        entityInstanceId = entityInstanceId,
        operationType    = op,
        fields           = fields,
        embedding        = embedding,
      )
    )

final case class SearchCurrentFactsRequest(
    embedding:           List[Double],
    personId:            Option[UUID],
    householdId:         Option[UUID],
    domainId:            Option[UUID],
    entityType:          Option[String],
    limit:               Option[Int],
    similarityThreshold: Option[Double],
) derives Codec.AsObject

final case class FactResponse(
    id:               UUID,
    documentId:       UUID,
    schemaId:         UUID,
    entityInstanceId: UUID,
    operationType:    String,
    fields:           Json,
    createdAt:        Instant,
) derives Codec.AsObject

object FactResponse:
  def fromDomain(f: Fact): FactResponse =
    FactResponse(
      id               = f.id,
      documentId       = f.documentId,
      schemaId         = f.schemaId,
      entityInstanceId = f.entityInstanceId,
      operationType    = f.operationType.toString.toLowerCase,
      fields           = f.fields,
      createdAt        = f.createdAt,
    )

final case class CurrentFactResponse(
    entityInstanceId: UUID,
    schemaId:         UUID,
    personId:         Option[UUID],
    householdId:      Option[UUID],
    fields:           Json,
    lastUpdatedAt:    Instant,
) derives Codec.AsObject

object CurrentFactResponse:
  def fromDomain(cf: CurrentFact): CurrentFactResponse =
    CurrentFactResponse(
      entityInstanceId = cf.entityInstanceId,
      schemaId         = cf.schemaId,
      personId         = cf.personId,
      householdId      = cf.householdId,
      fields           = cf.fields,
      lastUpdatedAt    = cf.lastUpdatedAt,
    )

final case class FactHistoryResponse(
    entityInstanceId: UUID,
    items:            List[FactResponse],
) derives Codec.AsObject

def parseOperationType(s: String): Either[String, OperationType] =
  s.toLowerCase match
    case "create" => Right(OperationType.Create)
    case "update" => Right(OperationType.Update)
    case "delete" => Right(OperationType.Delete)
    case other    => Left(s"Unknown operation type: '$other'. Valid values: create, update, delete")
