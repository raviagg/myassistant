package com.myassistant.db.repositories

import com.myassistant.domain.{AuditLog, InteractionStatus}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `audit_log` table. */
trait AuditRepository:
  /** Insert a new audit log entry. */
  def create(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog]

  /** Fetch an audit log entry by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[AuditLog]]

  /** List audit log entries for a person with pagination. */
  def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]]

  /** List audit log entries for a job type with pagination. */
  def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]]

object AuditRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, person_id, job_type, message, response, tool_calls, status, error, created_at
  private type AuditRow =
    (String, Option[String], Option[String], String, Option[String],
     String, String, Option[String], java.sql.Timestamp)

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToAuditLog(row: AuditRow): AuditLog =
    val (id, personId, jobType, message, response, toolCallsJson, status, error, createdAt) = row
    AuditLog(
      id        = UUID.fromString(id),
      personId  = personId.map(UUID.fromString),
      jobType   = jobType,
      message   = message,
      response  = response,
      toolCalls = circeParser.parse(toolCallsJson).getOrElse(Json.arr()),
      status    = status.toLowerCase match
        case "success" => InteractionStatus.Success
        case "partial" => InteractionStatus.Partial
        case "failed"  => InteractionStatus.Failed
        case other     => throw new IllegalArgumentException(s"Unknown interaction_status: $other"),
      error     = error,
      createdAt = createdAt.toInstant,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends AuditRepository:

    /** Insert a new audit log entry and return the persisted record. */
    def create(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog] =
      val statusStr    = entry.status match
        case InteractionStatus.Success => "success"
        case InteractionStatus.Partial => "partial"
        case InteractionStatus.Failed  => "failed"
      val toolCallsStr = entry.toolCalls.noSpaces
      transaction {
        sql"""
          INSERT INTO audit_log(
            id, person_id, job_type, message, response,
            tool_calls, status, error
          ) VALUES (
            ${entry.id.toString}::uuid,
            ${entry.personId.map(_.toString)}::uuid,
            ${entry.jobType},
            ${entry.message},
            ${entry.response},
            ${toolCallsStr}::jsonb,
            ${statusStr},
            ${entry.error}
          )
          RETURNING
            id::text,
            person_id::text,
            job_type,
            message,
            response,
            tool_calls::text,
            status,
            error,
            created_at
        """.query[AuditRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT audit_log returned no row"))))
        .map(rowToAuditLog)

    /** Fetch an audit log entry by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[AuditLog]] =
      transaction {
        sql"""
          SELECT
            id::text,
            person_id::text,
            job_type,
            message,
            response,
            tool_calls::text,
            status,
            error,
            created_at
          FROM audit_log
          WHERE id = ${id.toString}::uuid
        """.query[AuditRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToAuditLog))

    /** List audit log entries for a person, newest first, with pagination. */
    def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      transaction {
        sql"""
          SELECT
            id::text,
            person_id::text,
            job_type,
            message,
            response,
            tool_calls::text,
            status,
            error,
            created_at
          FROM audit_log
          WHERE person_id = ${personId.toString}::uuid
          ORDER BY created_at DESC
          LIMIT ${limit} OFFSET ${offset}
        """.query[AuditRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToAuditLog))

    /** List audit log entries for a job type, newest first, with pagination. */
    def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      transaction {
        sql"""
          SELECT
            id::text,
            person_id::text,
            job_type,
            message,
            response,
            tool_calls::text,
            status,
            error,
            created_at
          FROM audit_log
          WHERE job_type = ${jobType}
          ORDER BY created_at DESC
          LIMIT ${limit} OFFSET ${offset}
        """.query[AuditRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToAuditLog))

  /** ZLayer providing the live AuditRepository. */
  val live: ZLayer[Any, Nothing, AuditRepository] =
    ZLayer.succeed(new Live)
