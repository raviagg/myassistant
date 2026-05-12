package com.myassistant.domain

import java.time.Instant
import java.util.UUID

/** A recurring job definition that polls an external source on a cron schedule.
 *
 *  Each job is scoped to either a person or a household (enforced by DB CHECK
 *  constraint).  The `config` field holds source-specific parameters (e.g.
 *  NewsAPI topics, Plaid item ids) as an opaque JSON object.
 */
final case class ScheduledJob(
    id:             UUID,
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         io.circe.JsonObject,
    enabled:        Boolean,
    nextRunAt:      Option[Instant],
    createdAt:      Instant,
)

/** Lightweight create-request model (before DB assigns id / timestamp). */
final case class CreateScheduledJob(
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         io.circe.JsonObject,
    enabled:        Boolean,
)

/** Patch-style update model — all fields optional (None = no change).
 *  nextRunAt uses Option[Option[Instant]]: None = no change, Some(None) = set NULL, Some(Some(ts)) = set value.
 */
final case class UpdateScheduledJob(
    cronExpression: Option[String],
    config:         Option[io.circe.JsonObject],
    enabled:        Option[Boolean],
    nextRunAt:      Option[Option[Instant]],
)

/** A single execution record for a ScheduledJob. */
final case class ScheduledJobRun(
    id:             UUID,
    jobId:          UUID,
    startedAt:      Instant,
    finishedAt:     Option[Instant],
    status:         String,
    error:          Option[String],
    articlesStored: Int,
)
