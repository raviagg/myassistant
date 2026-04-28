package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** Immutable representation of one entry in the document store.
 *
 *  Every piece of information entering the system — typed by a user,
 *  uploaded as a file, or polled from an external source — becomes a
 *  document.  Documents are never updated or deleted; superseding is
 *  done by creating a new document with `supersedesIds` set.
 */
final case class Document(
    /** Unique database-generated identifier. */
    id: UUID,
    /** Owner person; may be null if owned only by a household. */
    personId: Option[UUID],
    /** Owner household; may be null if owned only by a person. */
    householdId: Option[UUID],
    /** Full raw text content of this document. */
    contentText: String,
    /** How this document entered the system (FK to source_type.name). */
    sourceType: String,
    /** JSON array of attached file metadata (path, type, original name). */
    files: io.circe.Json,
    /** IDs of documents this document supersedes. */
    supersedesIds: List[UUID],
    /** Timestamp of record creation. */
    createdAt: Instant,
)

/** Request model for creating a new document. */
final case class CreateDocument(
    personId:      Option[UUID],
    householdId:   Option[UUID],
    contentText:   String,
    sourceType:    String,
    files:         io.circe.Json,
    supersedesIds: List[UUID],
)
