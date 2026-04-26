package com.myassistant.api.models

import com.myassistant.domain.{CreateRelationship, Relationship, RelationType}
import io.circe.Codec

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /relationships. */
final case class CreateRelationshipRequest(
    fromPersonId: UUID,
    toPersonId:   UUID,
    relationType: String,
) derives Codec.AsObject

/** HTTP request body for PATCH /relationships/:id. */
final case class UpdateRelationshipRequest(
    relationType: String,
) derives Codec.AsObject

/** HTTP response body for a single relationship. */
final case class RelationshipResponse(
    id:           UUID,
    fromPersonId: UUID,
    toPersonId:   UUID,
    relationType: String,
    createdAt:    Instant,
    updatedAt:    Instant,
) derives Codec.AsObject

object RelationshipResponse:
  /** Build a RelationshipResponse from the domain Relationship. */
  def fromDomain(r: Relationship): RelationshipResponse =
    RelationshipResponse(
      id           = r.id,
      fromPersonId = r.fromPersonId,
      toPersonId   = r.toPersonId,
      relationType = r.relationType.toString.toLowerCase,
      createdAt    = r.createdAt,
      updatedAt    = r.updatedAt,
    )

/** HTTP response body for a kinship resolution query. */
final case class KinshipResponse(
    fromPersonId: UUID,
    toPersonId:   UUID,
    chain:        List[String],
    alias:        Option[String],
    description:  String,
) derives Codec.AsObject

/** Parse a lowercase relation type string to a domain RelationType. */
def parseRelationType(s: String): Either[String, RelationType] =
  s.toLowerCase match
    case "father"   => Right(RelationType.Father)
    case "mother"   => Right(RelationType.Mother)
    case "son"      => Right(RelationType.Son)
    case "daughter" => Right(RelationType.Daughter)
    case "brother"  => Right(RelationType.Brother)
    case "sister"   => Right(RelationType.Sister)
    case "husband"  => Right(RelationType.Husband)
    case "wife"     => Right(RelationType.Wife)
    case other      => Left(s"Unknown relation type: '$other'")
