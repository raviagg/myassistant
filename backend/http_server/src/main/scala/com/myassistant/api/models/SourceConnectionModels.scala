package com.myassistant.api.models

import com.myassistant.domain.SourceConnection
import io.circe.{Codec, JsonObject}

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /api/v1/source-connections.
 *
 *  `secrets` is the **plaintext** to be encrypted by the service layer
 *  with AES-256-GCM. The encrypted blob is what reaches the DB; the
 *  plaintext is never persisted and never returned by the API.
 *
 *  `secrets` defaults to None — many connectors (bulk file, news polling)
 *  do not require credentials.
 */
final case class CreateSourceConnectionRequest(
    sourceType:     String,
    connectionName: String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    config:         Option[JsonObject],
    secrets:        Option[String],
    syncScheduled:  Option[Boolean],
    syncAdhoc:      Option[Boolean],
    syncSchedule:   Option[String],
) derives Codec.AsObject

/** HTTP request body for PUT /api/v1/source-connections/{id}.
 *
 *  Same shape as the create body — PUT semantics overwrite every field.
 *  The exception is `secrets`: when omitted, null, or an empty string,
 *  the service preserves the previously-stored encrypted value rather
 *  than wiping it. To rotate a credential the caller supplies the new
 *  plaintext; to drop a credential the connection must be deleted and
 *  re-created.
 */
final case class UpdateSourceConnectionRequest(
    sourceType:     String,
    connectionName: String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    config:         Option[JsonObject],
    secrets:        Option[String],
    syncScheduled:  Option[Boolean],
    syncAdhoc:      Option[Boolean],
    syncSchedule:   Option[String],
) derives Codec.AsObject

/** HTTP response body for a single source_connection.
 *
 *  The `secrets` column is intentionally NOT included — credentials
 *  must never leave the server.
 */
final case class SourceConnectionResponse(
    id:             UUID,
    sourceType:     String,
    connectionName: String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    config:         JsonObject,
    syncScheduled:  Boolean,
    syncAdhoc:      Boolean,
    syncSchedule:   Option[String],
    nextRunAt:      Option[Instant],
    lastSyncedAt:   Option[Instant],
    status:         String,
    createdAt:      Instant,
    updatedAt:      Instant,
) derives Codec.AsObject

object SourceConnectionResponse:

  def fromDomain(c: SourceConnection): SourceConnectionResponse =
    SourceConnectionResponse(
      id             = c.id,
      sourceType     = c.sourceType,
      connectionName = c.connectionName,
      personId       = c.personId,
      householdId    = c.householdId,
      config         = c.config,
      syncScheduled  = c.syncScheduled,
      syncAdhoc      = c.syncAdhoc,
      syncSchedule   = c.syncSchedule,
      nextRunAt      = c.nextRunAt,
      lastSyncedAt   = c.lastSyncedAt,
      status         = c.status,
      createdAt      = c.createdAt,
      updatedAt      = c.updatedAt,
    )

/** Response body for POST /api/v1/source-connections/{id}/sync. */
final case class SyncQueuedResponse(
    message:      String,
    connectionId: UUID,
) derives Codec.AsObject

/** Request body for POST /api/v1/source-connections/{id}/advance.
 *
 *  Scheduler-internal endpoint that advances `next_run_at` after the
 *  scheduler has computed the next cron tick.
 */
final case class AdvanceNextRunRequest(
    nextRunAt: Instant,
) derives Codec.AsObject

/** Request body for POST /api/v1/source-connections/{id}/mark-synced. */
final case class MarkSyncedRequest(
    lastSyncedAt: Instant,
) derives Codec.AsObject

/** Response body for GET /api/v1/source-connections/{id}/secrets — the
 *  decrypted JSON object, or null when the column is empty.
 */
final case class SecretsResponse(
    secrets: io.circe.Json,
) derives Codec.AsObject
