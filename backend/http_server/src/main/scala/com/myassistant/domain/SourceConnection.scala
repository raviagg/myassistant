package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** A registered external data source connection (Plaid, Gmail, news, …).
 *
 *  Each connection is owned by exactly one person OR one household
 *  (enforced by the `sc_exactly_one_owner` DB CHECK constraint).
 *  Sync can be triggered two independent ways: cron-driven background
 *  polling (`syncScheduled`) and user-initiated "Refresh Now"
 *  (`syncAdhoc`). Both flags may be true simultaneously.
 *
 *  Sensitive credentials live in the DB-side `secrets` column as an
 *  AES-256-GCM encrypted blob. The plaintext is NEVER returned by any
 *  API endpoint; this domain model carries only the boolean
 *  `hasSecrets` to indicate whether an encrypted blob is present.
 */
final case class SourceConnection(
    id:             UUID,
    sourceType:     String,
    connectionName: String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    config:         io.circe.JsonObject,
    hasSecrets:     Boolean,
    syncScheduled:  Boolean,
    syncAdhoc:      Boolean,
    syncSchedule:   Option[String],
    nextRunAt:      Option[Instant],
    lastSyncedAt:   Option[Instant],
    status:         String,
    createdAt:      Instant,
    updatedAt:      Instant,
)

/** Lightweight create-request model — the repository assigns the id
 *  and timestamps, and stores the encrypted secrets blob (if any).
 *
 *  `secretsCiphertext` is the AES-256-GCM encrypted blob produced by
 *  `SecretsService.encrypt`. The repository writes it verbatim; the
 *  service layer is responsible for encryption.
 */
final case class CreateSourceConnection(
    sourceType:        String,
    connectionName:    String,
    personId:          Option[UUID],
    householdId:       Option[UUID],
    config:            io.circe.JsonObject,
    secretsCiphertext: Option[String],
    syncScheduled:     Boolean,
    syncAdhoc:         Boolean,
    syncSchedule:      Option[String],
)

/** Full-update model used by PUT /source-connections/{id}.
 *
 *  All fields except `secretsCiphertext` overwrite the stored values.
 *  When `secretsCiphertext` is None the stored secret is preserved
 *  (this is the "blank secrets in the request keeps the existing
 *  value" semantic from http-contract.md).
 */
final case class UpdateSourceConnection(
    sourceType:        String,
    connectionName:    String,
    personId:          Option[UUID],
    householdId:       Option[UUID],
    config:            io.circe.JsonObject,
    secretsCiphertext: Option[String],
    syncScheduled:     Boolean,
    syncAdhoc:         Boolean,
    syncSchedule:      Option[String],
)
