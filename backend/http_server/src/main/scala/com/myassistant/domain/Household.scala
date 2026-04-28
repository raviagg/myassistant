package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** A household grouping one or more persons together.
 *
 *  Represents a shared living unit or family group.  Household-level
 *  information (address, shared expenses, utilities) lives in fact tables
 *  under the household domain.
 */
final case class Household(
    /** Unique database-generated identifier. */
    id: UUID,
    /** Human-readable label such as "The Sharma Family". */
    name: String,
    /** Timestamp of record creation. */
    createdAt: Instant,
    /** Timestamp of last modification. */
    updatedAt: Instant,
)

/** Request model for creating a new household. */
final case class CreateHousehold(name: String)

/** Patch-style update model. */
final case class UpdateHousehold(name: Option[String])
