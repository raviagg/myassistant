package com.myassistant.domain

import java.time.Instant

/** Reference record for a data source in the governed vocabulary.
 *
 *  New sources (calendar_poll, apple_health_poll, …) are added as rows.
 */
final case class SourceType(
    /** Machine-readable identifier, snake_case lowercase. */
    name: String,
    /** Human-readable explanation of how documents from this source are produced. */
    description: String,
    /** Timestamp of record creation. */
    createdAt: Instant,
)
