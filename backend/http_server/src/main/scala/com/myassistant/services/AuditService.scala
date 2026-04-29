package com.myassistant.services

import com.myassistant.db.repositories.AuditRepository
import com.myassistant.domain.{AuditLog, InteractionStatus}
import com.myassistant.errors.AppError
import io.circe.Json
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for writing and querying audit log entries. */
trait AuditService:
  /** Append a new interaction record to the audit log. */
  def log(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog]

  /** Retrieve an audit log entry by id. */
  def getEntry(id: UUID): ZIO[ZConnectionPool, AppError, AuditLog]

  /** List audit entries for a person with pagination. */
  def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]]

  /** List audit entries for a polling job type with pagination. */
  def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]]

object AuditService:

  /** Live implementation backed by AuditRepository. */
  final class Live(repo: AuditRepository) extends AuditService:

    /** Validate that exactly one of personId or jobType is set, then persist the entry.
     *
     *  The AuditLog domain model accepts a pre-constructed entry including its UUID.
     *  Callers must supply a generated UUID (e.g. UUID.randomUUID()) as the id field.
     */
    def log(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog] =
      val hasPersonId = entry.personId.isDefined
      val hasJobType  = entry.jobType.isDefined
      if hasPersonId && hasJobType then
        ZIO.fail(AppError.ValidationError("Only one of personId or jobType may be set"))
      else if !hasPersonId && !hasJobType then
        ZIO.fail(AppError.ValidationError("Exactly one of personId or jobType must be set"))
      else
        repo.create(entry)

    /** Retrieve an audit log entry by id; fails with NotFound if absent. */
    def getEntry(id: UUID): ZIO[ZConnectionPool, AppError, AuditLog] =
      repo.findById(id).flatMap:
        case Some(e) => ZIO.succeed(e)
        case None    => ZIO.fail(AppError.NotFound("audit_log", id.toString))

    /** List audit log entries for a person with pagination, newest first. */
    def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      repo.listByPerson(personId, limit, offset)

    /** List audit log entries for a job type with pagination, newest first. */
    def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      repo.listByJobType(jobType, limit, offset)

  /** ZLayer providing the live AuditService. */
  val live: ZLayer[AuditRepository, Nothing, AuditService] =
    ZLayer.fromFunction(new Live(_))
