package com.myassistant.db.repositories

import com.myassistant.domain.{AuditLog, InteractionStatus}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

trait AuditRepository:
  def create(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog]
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[AuditLog]]
  def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]]
  def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]]

object AuditRepository:

  // id, person_id, job_type, message_text, response_text, tool_calls_json, status, error_message, created_at
  private type AuditRow =
    (String, Option[String], Option[String], String, String,
     Option[String], String, Option[String], java.sql.Timestamp)

  private def rowToAuditLog(row: AuditRow): AuditLog =
    val (id, personId, jobType, messageText, responseText, toolCallsJson, status, errorMessage, createdAt) = row
    AuditLog(
      id            = UUID.fromString(id),
      personId      = personId.map(UUID.fromString),
      jobType       = jobType,
      messageText   = messageText,
      responseText  = responseText,
      toolCallsJson = toolCallsJson.flatMap(circeParser.parse(_).toOption),
      status        = status.toLowerCase match
        case "success" => InteractionStatus.Success
        case "partial" => InteractionStatus.Partial
        case "failed"  => InteractionStatus.Failed
        case other     => throw new IllegalArgumentException(s"Unknown interaction_status: $other"),
      errorMessage  = errorMessage,
      createdAt     = createdAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  final class Live extends AuditRepository:

    def create(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog] =
      val statusStr      = entry.status match
        case InteractionStatus.Success => "success"
        case InteractionStatus.Partial => "partial"
        case InteractionStatus.Failed  => "failed"
      val toolCallsStr   = entry.toolCallsJson.map(_.noSpaces)
      transaction {
        sql"""
          INSERT INTO audit_log(
            id, person_id, job_type, message_text, response_text,
            tool_calls_json, status, error_message
          ) VALUES (
            ${entry.id.toString}::uuid,
            ${entry.personId.map(_.toString)}::uuid,
            ${entry.jobType},
            ${entry.messageText},
            ${entry.responseText},
            ${toolCallsStr}::jsonb,
            ${statusStr},
            ${entry.errorMessage}
          )
          RETURNING
            id::text,
            person_id::text,
            job_type,
            message_text,
            response_text,
            tool_calls_json::text,
            status,
            error_message,
            created_at
        """.query[AuditRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT audit_log returned no row"))))
        .map(rowToAuditLog)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[AuditLog]] =
      transaction {
        sql"""
          SELECT
            id::text, person_id::text, job_type,
            message_text, response_text, tool_calls_json::text,
            status, error_message, created_at
          FROM audit_log
          WHERE id = ${id.toString}::uuid
        """.query[AuditRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToAuditLog))

    def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      transaction {
        sql"""
          SELECT
            id::text, person_id::text, job_type,
            message_text, response_text, tool_calls_json::text,
            status, error_message, created_at
          FROM audit_log
          WHERE person_id = ${personId.toString}::uuid
          ORDER BY created_at DESC
          LIMIT ${limit} OFFSET ${offset}
        """.query[AuditRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToAuditLog))

    def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      transaction {
        sql"""
          SELECT
            id::text, person_id::text, job_type,
            message_text, response_text, tool_calls_json::text,
            status, error_message, created_at
          FROM audit_log
          WHERE job_type = ${jobType}
          ORDER BY created_at DESC
          LIMIT ${limit} OFFSET ${offset}
        """.query[AuditRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToAuditLog))

  val live: ZLayer[Any, Nothing, AuditRepository] =
    ZLayer.succeed(new Live)
