package com.myassistant.api.models

import com.myassistant.domain.{CreateScheduledJob, ScheduledJob, ScheduledJobRun, UpdateScheduledJob}
import io.circe.{Codec, JsonObject}

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /scheduled-jobs. */
final case class CreateScheduledJobRequest(
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         Option[JsonObject],
    enabled:        Option[Boolean],
) derives Codec.AsObject:

  def toDomain: CreateScheduledJob =
    CreateScheduledJob(
      sourceType     = sourceType,
      personId       = personId,
      householdId    = householdId,
      cronExpression = cronExpression,
      config         = config.getOrElse(JsonObject.empty),
      enabled        = enabled.getOrElse(true),
    )

/** HTTP request body for PATCH /scheduled-jobs/:id. */
final case class UpdateScheduledJobRequest(
    cronExpression: Option[String],
    config:         Option[JsonObject],
    enabled:        Option[Boolean],
    nextRunAt:      Option[Instant],
) derives Codec.AsObject:

  def toDomain: UpdateScheduledJob =
    UpdateScheduledJob(
      cronExpression = cronExpression,
      config         = config,
      enabled        = enabled,
      nextRunAt      = nextRunAt.map(Some(_)),
    )

/** HTTP response body for a single scheduled job. */
final case class ScheduledJobResponse(
    id:             UUID,
    sourceType:     String,
    personId:       Option[UUID],
    householdId:    Option[UUID],
    cronExpression: String,
    config:         JsonObject,
    enabled:        Boolean,
    nextRunAt:      Option[Instant],
    createdAt:      Instant,
) derives Codec.AsObject

object ScheduledJobResponse:
  def fromDomain(j: ScheduledJob): ScheduledJobResponse =
    ScheduledJobResponse(
      id             = j.id,
      sourceType     = j.sourceType,
      personId       = j.personId,
      householdId    = j.householdId,
      cronExpression = j.cronExpression,
      config         = j.config,
      enabled        = j.enabled,
      nextRunAt      = j.nextRunAt,
      createdAt      = j.createdAt,
    )

/** HTTP request body for POST /scheduled-jobs/:id/runs. */
final case class CreateScheduledJobRunRequest(
    status:         String,
    finishedAt:     Option[Instant],
    error:          Option[String],
    articlesStored: Option[Int],
) derives Codec.AsObject

/** HTTP response body for a single scheduled job run. */
final case class ScheduledJobRunResponse(
    id:             UUID,
    jobId:          UUID,
    startedAt:      Instant,
    finishedAt:     Option[Instant],
    status:         String,
    error:          Option[String],
    articlesStored: Int,
) derives Codec.AsObject

object ScheduledJobRunResponse:
  def fromDomain(r: ScheduledJobRun): ScheduledJobRunResponse =
    ScheduledJobRunResponse(
      id             = r.id,
      jobId          = r.jobId,
      startedAt      = r.startedAt,
      finishedAt     = r.finishedAt,
      status         = r.status,
      error          = r.error,
      articlesStored = r.articlesStored,
    )
