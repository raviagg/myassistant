package com.myassistant.api.models

import com.myassistant.domain.{CreateDocument, Document}
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /documents. */
final case class CreateDocumentRequest(
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceType:    String,
    files:         Json,
    supersedesIds: List[UUID],
) derives Codec.AsObject:

  /** Convert to domain CreateDocument. */
  def toDomain: CreateDocument =
    CreateDocument(
      personId      = personId,
      householdId   = householdId,
      contentText   = contentText,
      sourceType    = sourceType,
      files         = files,
      supersedesIds = supersedesIds,
    )

/** HTTP request body for POST /documents/search. */
final case class SearchDocumentsRequest(
    query:       String,
    personId:    Option[UUID],
    householdId: Option[UUID],
    limit:       Option[Int],
) derives Codec.AsObject

/** HTTP response body for a single document. */
final case class DocumentResponse(
    id:            UUID,
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceType:    String,
    files:         Json,
    supersedesIds: List[UUID],
    createdAt:     Instant,
) derives Codec.AsObject

object DocumentResponse:
  /** Build a DocumentResponse from the domain Document. */
  def fromDomain(d: Document): DocumentResponse =
    DocumentResponse(
      id            = d.id,
      personId      = d.personId,
      householdId   = d.householdId,
      contentText   = d.contentText,
      sourceType    = d.sourceType,
      files         = d.files,
      supersedesIds = d.supersedesIds,
      createdAt     = d.createdAt,
    )
