package com.myassistant.api.models

import com.myassistant.domain.{CreateDocument, Document}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

final case class CreateDocumentRequest(
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceTypeId:  UUID,
    embedding:     List[Double],
    files:         Json,
    supersedesIds: List[UUID],
) derives Codec.AsObject:

  def toDomain: CreateDocument =
    CreateDocument(
      personId      = personId,
      householdId   = householdId,
      contentText   = contentText,
      sourceTypeId  = sourceTypeId,
      embedding     = embedding,
      files         = files,
      supersedesIds = supersedesIds,
    )

final case class SearchDocumentsRequest(
    embedding:           List[Double],
    personId:            Option[UUID],
    householdId:         Option[UUID],
    sourceTypeId:        Option[UUID],
    limit:               Option[Int],
    similarityThreshold: Option[Double],
) derives Codec.AsObject

final case class DocumentResponse(
    id:            UUID,
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceTypeId:  UUID,
    files:         Json,
    supersedesIds: List[UUID],
    createdAt:     Instant,
) derives Codec.AsObject

object DocumentResponse:
  def fromDomain(d: Document): DocumentResponse =
    DocumentResponse(
      id            = d.id,
      personId      = d.personId,
      householdId   = d.householdId,
      contentText   = d.contentText,
      sourceTypeId  = d.sourceTypeId,
      files         = d.files,
      supersedesIds = d.supersedesIds,
      createdAt     = d.createdAt,
    )
