package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** Membership record linking a person to a household.
 *
 *  Represents one side of the N:N relationship between persons and
 *  households.  A person can belong to multiple households; a household
 *  can contain multiple persons.
 */
final case class PersonHousehold(
    /** FK to person.id. */
    personId: UUID,
    /** FK to household.id. */
    householdId: UUID,
    /** Timestamp when membership was established. */
    createdAt: Instant,
)
