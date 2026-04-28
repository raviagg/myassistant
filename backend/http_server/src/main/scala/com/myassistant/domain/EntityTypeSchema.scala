package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** One versioned schema definition for a (domain, entityType) pair.
 *
 *  Governs what fields can be extracted for a given entity type.
 *  All versions are retained; current schema = highest version where isActive=true.
 */
final case class EntityTypeSchema(
    /** Unique database-generated identifier; referenced by fact.schemaId. */
    id: UUID,
    /** Life domain this entity type belongs to (FK to domain.name). */
    domain: String,
    /** Machine-readable entity type name within its domain. */
    entityType: String,
    /** Schema version number, starts at 1 and increments on evolution. */
    schemaVersion: Int,
    /** Human-readable description of what this entity type captures. */
    description: String,
    /** JSONB array defining the fields: name, type, mandatory, description. */
    fieldDefinitions: io.circe.Json,
    /** Auto-generated list of mandatory field names (DB generated column). */
    mandatoryFields: List[String],
    /** Prompt fragment given to the LLM during fact extraction. */
    extractionPrompt: String,
    /** Whether this schema version is currently in use. */
    isActive: Boolean,
    /** What changed compared to the previous version (null for v1). */
    changeDescription: Option[String],
    /** Timestamp of record creation. */
    createdAt: Instant,
)

/** Request model for proposing a new entity type schema. */
final case class ProposeEntityTypeSchema(
    domain:           String,
    entityType:       String,
    description:      String,
    fieldDefinitions: io.circe.Json,
    extractionPrompt: String,
    changeDescription: Option[String],
)
