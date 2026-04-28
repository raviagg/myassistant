package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** Operation types for the append-only fact stream. */
enum OperationType:
  case Create, Update, Delete

/** One row in the append-only fact operation stream.
 *
 *  Facts are extracted from documents and stored as create/update/delete
 *  operations.  Current state is derived by merging all operations for an
 *  `entityInstanceId` in chronological order (patch semantics).
 */
final case class Fact(
    /** Unique database-generated row identifier. */
    id: UUID,
    /** FK to the source document. */
    documentId: UUID,
    /** FK to the entity_type_schema version used during extraction. */
    schemaId: UUID,
    /** Groups all operations on the same logical entity. */
    entityInstanceId: UUID,
    /** Whether this row creates, patches, or deletes the entity. */
    operationType: OperationType,
    /** JSONB field values for this operation (patch for update rows). */
    fields: io.circe.Json,
    /** Timestamp when this operation row was written. */
    createdAt: Instant,
)

/** Request model for creating a new fact operation row. */
final case class CreateFact(
    documentId:       UUID,
    schemaId:         UUID,
    entityInstanceId: Option[UUID],   // None → generate new UUID (create op)
    operationType:    OperationType,
    fields:           io.circe.Json,
)
