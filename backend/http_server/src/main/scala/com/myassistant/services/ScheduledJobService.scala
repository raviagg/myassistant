package com.myassistant.services

import com.myassistant.api.models.{
  CreateScheduledJobRequest, ScheduledJobResponse, ScheduledJobRunResponse, UpdateScheduledJobRequest
}
import com.myassistant.db.repositories.ScheduledJobRepository
import com.myassistant.domain.ScheduledJobRun
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

trait ScheduledJobService:
  def create(req: CreateScheduledJobRequest): ZIO[ZConnectionPool, AppError, ScheduledJobResponse]
  def getById(id: UUID): ZIO[ZConnectionPool, AppError, Option[ScheduledJobResponse]]
  def listByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]]
  def listByHousehold(householdId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]]
  def listDue(): ZIO[ZConnectionPool, AppError, List[ScheduledJobResponse]]
  def update(id: UUID, req: UpdateScheduledJobRequest): ZIO[ZConnectionPool, AppError, Option[ScheduledJobResponse]]
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]
  def createRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRunResponse]
  def getRunsByJobId(jobId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobRunResponse]]

object ScheduledJobService:

  final class Live(repo: ScheduledJobRepository) extends ScheduledJobService:

    def create(req: CreateScheduledJobRequest): ZIO[ZConnectionPool, AppError, ScheduledJobResponse] =
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
      repo.update(id, req.toDomain).map(_.map(ScheduledJobResponse.fromDomain))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      repo.delete(id)

    def createRun(run: ScheduledJobRun): ZIO[ZConnectionPool, AppError, ScheduledJobRunResponse] =
      repo.createRun(run).map(ScheduledJobRunResponse.fromDomain)

    def getRunsByJobId(jobId: UUID): ZIO[ZConnectionPool, AppError, List[ScheduledJobRunResponse]] =
      repo.findRunsByJobId(jobId).map(_.map(ScheduledJobRunResponse.fromDomain))

  val live: ZLayer[ScheduledJobRepository, Nothing, ScheduledJobService] =
    ZLayer.fromFunction(new Live(_))
