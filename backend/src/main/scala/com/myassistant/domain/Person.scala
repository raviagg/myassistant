package com.myassistant.domain

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Gender enumeration aligned with the `gender_type` Postgres enum. */
enum Gender:
  case Male, Female

/** Core identity record for a person in the system.
 *
 *  Intentionally minimal — only stable, rarely-changing fields live here.
 *  All additional personal information lives in fact tables under the
 *  personal_details domain.
 */
final case class Person(
    /** Unique database-generated identifier. */
    id: UUID,
    /** Full legal or commonly used name. */
    fullName: String,
    /** Gender, used for kinship resolution. */
    gender: Gender,
    /** Date of birth; optional — may not be known for all persons. */
    dateOfBirth: Option[LocalDate],
    /** Nickname or commonly used short name. */
    preferredName: Option[String],
    /** Login identifier (email) for persons who access the system. */
    userIdentifier: Option[String],
    /** Timestamp of record creation. */
    createdAt: Instant,
    /** Timestamp of last modification (maintained by DB trigger). */
    updatedAt: Instant,
)

/** Lightweight create-request model (before DB assigns id / timestamps). */
final case class CreatePerson(
    fullName:       String,
    gender:         Gender,
    dateOfBirth:    Option[LocalDate],
    preferredName:  Option[String],
    userIdentifier: Option[String],
)

/** Patch-style update model — all fields optional (None = no change). */
final case class UpdatePerson(
    fullName:       Option[String],
    gender:         Option[Gender],
    dateOfBirth:    Option[LocalDate],
    preferredName:  Option[String],
    userIdentifier: Option[String],
)
