package com.myassistant.domain

import java.time.Instant

/** Reference record for a life domain in the governed vocabulary.
 *
 *  New domains are added as rows — no DDL change needed.
 */
final case class Domain(
    /** Machine-readable identifier, snake_case lowercase. */
    name: String,
    /** Human-readable explanation of what this domain covers. */
    description: String,
    /** Timestamp of record creation. */
    createdAt: Instant,
)
