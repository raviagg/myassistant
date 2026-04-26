package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** Interaction status outcomes. */
enum InteractionStatus:
  case Success, Partial, Failed

/** One row in the append-only audit log.
 *
 *  Records every interaction — from a human via chat or from a polling job.
 *  Exactly one of `personId` or `jobType` must be non-null.
 */
final case class AuditLog(
    /** Unique database-generated identifier. */
    id: UUID,
    /** Set for chatbot interactions; null for system jobs. */
    personId: Option[UUID],
    /** Set for polling job interactions; null for chatbot interactions. */
    jobType: Option[String],
    /** The incoming message that triggered this interaction. */
    message: String,
    /** The agent's response; null if the interaction failed before responding. */
    response: Option[String],
    /** JSONB array of all MCP tool calls made during this interaction. */
    toolCalls: io.circe.Json,
    /** Outcome of this interaction. */
    status: InteractionStatus,
    /** Error message if status is Failed or Partial. */
    error: Option[String],
    /** Timestamp when this interaction occurred. */
    createdAt: Instant,
)
