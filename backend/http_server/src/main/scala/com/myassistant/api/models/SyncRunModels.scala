package com.myassistant.api.models

import com.myassistant.domain.{LatestSyncRuns, SyncRun}
import io.circe.{Codec, Json, JsonObject}

import java.time.Instant
import java.util.UUID

/** PATCH body for `/source-connections/{id}/runs/{run_id}`.
 *
 *  Every field is optional — only fields that are present in the JSON
 *  request body are written. Used by the connector worker to record
 *  terminal status, completion time, stats, and structured log lines
 *  for a sync_runs row that was previously inserted with
 *  `status='running'`.
 */
final case class PatchSyncRunRequest(
    status:      Option[String],
    completedAt: Option[Instant],
    stats:       Option[JsonObject],
    logLines:    Option[Json],
) derives Codec.AsObject

/** HTTP response body for a single sync_runs row.
 *
 *  `logLines` is exposed as a typed list of JSON objects. When the
 *  underlying JSONB cannot be coerced into a list of objects (e.g.
 *  malformed legacy data), we fall back to an empty list so clients
 *  can always rely on the shape.
 */
final case class SyncRunResponse(
    id:                 UUID,
    sourceConnectionId: UUID,
    runType:            String,
    status:             String,
    startedAt:          Instant,
    completedAt:        Option[Instant],
    stats:              Option[JsonObject],
    logLines:           List[JsonObject],
) derives Codec.AsObject

object SyncRunResponse:

  def fromDomain(r: SyncRun): SyncRunResponse =
    val lines: List[JsonObject] = r.logLines.asArray match
      case Some(arr) => arr.toList.flatMap(_.asObject)
      case None      => List.empty
    SyncRunResponse(
      id                 = r.id,
      sourceConnectionId = r.sourceConnectionId,
      runType            = r.runType,
      status             = r.status,
      startedAt          = r.startedAt,
      completedAt        = r.completedAt,
      stats              = r.stats,
      logLines           = lines,
    )

/** Response body for `GET /source-connections/{id}/runs/latest`. */
final case class LatestSyncRunsResponse(
    lastAdhoc:     Option[SyncRunResponse],
    lastScheduled: Option[SyncRunResponse],
) derives Codec.AsObject

object LatestSyncRunsResponse:
  def fromDomain(l: LatestSyncRuns): LatestSyncRunsResponse =
    LatestSyncRunsResponse(
      lastAdhoc     = l.lastAdhoc.map(SyncRunResponse.fromDomain),
      lastScheduled = l.lastScheduled.map(SyncRunResponse.fromDomain),
    )
