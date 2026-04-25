package com.myassistant.api.models

import com.myassistant.domain.{CreateFact, Fact, OperationType}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /facts. */
final case class CreateFactRequest(
    documentId:       UUID,
    schemaId:         UUID,
    entityInstanceId: Option[UUID],
    operationType:    String,
    fields:           Json,
) derives Codec.AsObject:

  /** Convert to domain CreateFact; caller must validate operationType beforehand. */
  def toDomain: Either[String, CreateFact] =
    parseOperationType(operationType).map(op =>
      CreateFact(
        documentId       = documentId,
        schemaId         = schemaId,
        entityInstanceId = entityInstanceId,
        operationType    = op,
        fields           = fields,
      )
    )

/** HTTP request body for POST /facts/search. */
final case class SearchFactsRequest(
    query:  String,
    domain: Option[String],
    limit:  Option[Int],
) derives Codec.AsObject

/** HTTP response body for a single fact operation row. */
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
  /** Build a FactResponse from the domain Fact. */
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

/** Parse a lowercase operation type string to a domain OperationType. */
def parseOperationType(s: String): Either[String, OperationType] =
  s.toLowerCase match
    case "create" => Right(OperationType.Create)
    case "update" => Right(OperationType.Update)
    case "delete" => Right(OperationType.Delete)
    case other    => Left(s"Unknown operation type: '$other'. Valid values: create, update, delete")
