package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** A single execution of a `source_connection` sync.
 *
 *  Every trigger of a connection — scheduled, adhoc, or re_extract —
 *  inserts one row with `status='running'`. The connector worker
 *  updates the row to a terminal status (success / warning / failed)
 *  on completion, writing `stats` and appending entries to `logLines`.
 *
 *  Rows are append-only — once `completedAt` is set the row is final.
 *
 *  `logLines` is stored as the raw JSONB array (typically a
 *  `Json.Array` of objects with `time`, `level`, `msg` keys). The API
 *  response model deserialises it to a `List[JsonObject]` for clients.
 */
final case class SyncRun(
    id:                 UUID,
    sourceConnectionId: UUID,
    runType:            String,
    status:             String,
    startedAt:          Instant,
    completedAt:        Option[Instant],
    stats:              Option[io.circe.JsonObject],
    logLines:           io.circe.Json,
)

/** Aggregate result for `GET /source-connections/{id}/runs/latest` —
 *  the most-recent run of each user-visible run type for a connection.
 *  Either field may be null when no run of that type has occurred.
 */
final case class LatestSyncRuns(
    lastAdhoc:     Option[SyncRun],
    lastScheduled: Option[SyncRun],
)
