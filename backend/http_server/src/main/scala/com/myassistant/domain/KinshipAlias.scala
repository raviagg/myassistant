package com.myassistant.domain

import java.time.Instant

/** Maps a chain of depth-1 relation types to a named alias in a language.
 *
 *  Supports cultural names for derived relations (bua, mama, nani, etc.)
 *  across multiple languages without storing transitive relationships.
 */
final case class KinshipAlias(
    /** Auto-incremented integer primary key. */
    id: Int,
    /** Ordered chain of depth-1 relation types, e.g. [father, sister]. */
    relationChain: List[String],
    /** Language of the alias, lowercase, e.g. "hindi", "english". */
    language: String,
    /** The culturally specific name, e.g. "bua" for father's sister in Hindi. */
    alias: String,
    /** Plain English description of the relation chain. */
    description: Option[String],
    /** Timestamp of record creation. */
    createdAt: Instant,
)
