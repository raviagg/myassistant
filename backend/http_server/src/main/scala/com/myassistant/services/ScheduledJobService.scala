package com.myassistant.services

import com.myassistant.api.models.{
  CreateScheduledJobRequest, CreateScheduledJobRunRequest, ScheduledJobResponse, ScheduledJobRunResponse, UpdateScheduledJobRequest
}
import com.myassistant.db.repositories.ScheduledJobRepository
import com.myassistant.domain.ScheduledJobRun
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.time.Instant
import java.util.UUID

trait ScheduledJobService:
  def create(req: CreateScheduledJobRequest): ZIO[ZConnectionPool, AppError, ScheduledJobResponse]
  def getById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJobResponse]]
  def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]]
  def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]]
  def listDue(): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]]
  def update(id: UUID, req: UpdateScheduledJobRequest): ZIO[ZConnectionPool, AppError, Option[ScheduledJobResponse]]
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]
  def createRun(jobId: UUID, req: CreateScheduledJobRunRequest): ZIO[ZConnectionPool, AppError, ScheduledJobRunResponse]
  def getRunsByJobId(jobId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobRunResponse]]

object ScheduledJobService:

  final class Live(repo: ScheduledJobRepository) extends ScheduledJobService:

    def create(req: CreateScheduledJobRequest): ZIO[ZConnectionPool, AppError, ScheduledJobResponse] =
      if req.personId.isEmpty && req.householdId.isEmpty then
        ZIO.fail(AppError.ValidationError("A scheduled job must be scoped to a person or a household"))
      else if req.personId.isDefined && req.householdId.isDefined then
        ZIO.fail(AppError.ValidationError("A scheduled job cannot be scoped to both a person and a household"))
      else
        repo.create(req.toDomain).map(ScheduledJobResponse.fromDomain)

    def getById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJobResponse]] =
      repo.findById(id).map(_.map(ScheduledJobResponse.fromDomain))

    def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]] =
      repo.findByPersonId(personId).map(_.map(ScheduledJobResponse.fromDomain))

    def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]] =
      repo.findByHouseholdId(householdId).map(_.map(ScheduledJobResponse.fromDomain))

    def listDue(): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]] =
      repo.findDueJobs().map(_.map(ScheduledJobResponse.fromDomain))

    def update(id: UUID, req: UpdateScheduledJobRequest): ZIO[ZConnectionPool, AppError, Option[ScheduledJobResponse]] =
      // When cron changes without an explicit nextRunAt, reset to NULL so the
      // scheduler fires it on the next poll and recalculates the proper next time.
      val domain = req.toDomain
      val effective = if req.cronExpression.isDefined && req.nextRunAt.isEmpty then
        domain.copy(nextRunAt = Some(None))
      else
        domain
      repo.update(id, effective).map(_.map(ScheduledJobResponse.fromDomain))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      repo.delete(id)

    def createRun(jobId: UUID, req: CreateScheduledJobRunRequest): ZIO[ZConnectionPool, AppError, ScheduledJobRunResponse] =
      val validStatuses = Set("success", "failure", "skipped")
      if !validStatuses.contains(req.status) then
        ZIO.fail(AppError.ValidationError(s"status must be one of: ${validStatuses.mkString(", ")}"))
      else
        repo.findById(jobId).flatMap {
          case None => ZIO.fail(AppError.NotFound("scheduled_job", jobId.toString))
          case Some(_) =>
            val run = ScheduledJobRun(
              id           = UUID.randomUUID(),
              jobId        = jobId,
              startedAt    = Instant.now(),
              finishedAt   = req.finishedAt,
              status       = req.status,
              statusDetail = req.statusDetail,
            )
            repo.createRun(run).map(ScheduledJobRunResponse.fromDomain)
        }

    def getRunsByJobId(jobId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobRunResponse]] =
      repo.findRunsByJobId(jobId).map(_.map(ScheduledJobRunResponse.fromDomain))

  val live: ZLayer[ScheduledJobRepository, Nothing, ScheduledJobService] =
    ZLayer.fromFunction(new Live(_))
