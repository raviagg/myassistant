package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** Eight atomic depth-1 relationship types stored in the DB.
 *
 *  All deeper relations (grandfather, aunt, cousin etc.) are derived at
 *  query time by traversing the relationship graph.
 */
enum RelationType:
  case Father, Mother, Son, Daughter, Brother, Sister, Husband, Wife

/** A directed depth-1 relationship between two persons.
 *
 *  Semantics: `fromPersonId IS [relationType] OF toPersonId`.
 *  Example: fromPersonId=Raj, relationType=Father, toPersonId=Arjun
 *  means "Raj is father of Arjun".
 */
final case class Relationship(
    /** Unique database-generated identifier. */
    id: UUID,
    /** The person holding the relation_type role. */
    fromPersonId: UUID,
    /** The person toward whom the relation is directed. */
    toPersonId: UUID,
    /** The relationship type (directional: describes fromPerson's role). */
    relationType: RelationType,
    /** Timestamp of record creation. */
    createdAt: Instant,
    /** Timestamp of last modification. */
    updatedAt: Instant,
)

/** Request model for creating a new relationship. */
final case class CreateRelationship(
    fromPersonId: UUID,
    toPersonId:   UUID,
    relationType: RelationType,
)
