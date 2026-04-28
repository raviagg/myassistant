package com.myassistant.db.repositories

import com.myassistant.domain.{CreateFact, Fact, OperationType}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `fact` table. */
trait FactRepository:
  /** Insert a new fact operation row. */
  def create(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact]

  /** Fetch a fact row by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Fact]]

  /** Return all fact rows for a given entity instance, newest first. */
  def findByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]

  /** Return all fact rows for a given document. */
  def findByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]

  /** Return all fact rows for a given schema, with pagination. */
  def findBySchema(schemaId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[Fact]]

object FactRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, document_id, schema_id, entity_instance_id, operation_type, fields, created_at
  private type FactRow =
    (String, String, String, String, String, String, java.sql.Timestamp)

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToFact(row: FactRow): Fact =
    val (id, documentId, schemaId, entityInstanceId, opType, fieldsJson, createdAt) = row
    Fact(
      id               = UUID.fromString(id),
      documentId       = UUID.fromString(documentId),
      schemaId         = UUID.fromString(schemaId),
      entityInstanceId = UUID.fromString(entityInstanceId),
      operationType    = opType.toLowerCase match
        case "create" => OperationType.Create
        case "update" => OperationType.Update
        case "delete" => OperationType.Delete
        case other    => throw new IllegalArgumentException(s"Unknown operation_type: $other"),
      fields           = circeParser.parse(fieldsJson).getOrElse(Json.obj()),
      createdAt        = createdAt.toInstant,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends FactRepository:

    /** Insert a new fact operation row and return the persisted record. */
    def create(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact] =
      val id               = UUID.randomUUID()
      val entityInstanceId = req.entityInstanceId.getOrElse(UUID.randomUUID())
      val opStr            = req.operationType match
        case OperationType.Create => "create"
        case OperationType.Update => "update"
        case OperationType.Delete => "delete"
      val fieldsStr        = req.fields.noSpaces
      transaction {
        sql"""
          INSERT INTO fact(id, document_id, schema_id, entity_instance_id, operation_type, fields)
          VALUES (
            ${id.toString}::uuid,
            ${req.documentId.toString}::uuid,
            ${req.schemaId.toString}::uuid,
            ${entityInstanceId.toString}::uuid,
            ${opStr}::operation_type,
            ${fieldsStr}::jsonb
          )
          RETURNING
            id::text,
            document_id::text,
            schema_id::text,
            entity_instance_id::text,
            operation_type::text,
            fields::text,
            created_at
        """.query[FactRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT fact returned no row"))))
        .map(rowToFact)

    /** Fetch a fact row by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text,
            document_id::text,
            schema_id::text,
            entity_instance_id::text,
            operation_type::text,
            fields::text,
            created_at
          FROM fact
          WHERE id = ${id.toString}::uuid
        """.query[FactRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToFact))

    /** Return all fact rows for a given entity instance, newest first. */
    def findByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text,
            document_id::text,
            schema_id::text,
            entity_instance_id::text,
            operation_type::text,
            fields::text,
            created_at
          FROM fact
          WHERE entity_instance_id = ${entityInstanceId.toString}::uuid
          ORDER BY created_at DESC
        """.query[FactRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToFact))

    /** Return all fact rows for a given document, oldest first. */
    def findByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text,
            document_id::text,
            schema_id::text,
            entity_instance_id::text,
            operation_type::text,
            fields::text,
            created_at
          FROM fact
          WHERE document_id = ${documentId.toString}::uuid
          ORDER BY created_at
        """.query[FactRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToFact))

    /** Return fact rows for a given schema with pagination, newest first. */
    def findBySchema(schemaId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text,
            document_id::text,
            schema_id::text,
            entity_instance_id::text,
            operation_type::text,
            fields::text,
            created_at
          FROM fact
          WHERE schema_id = ${schemaId.toString}::uuid
          ORDER BY created_at DESC
          LIMIT ${limit} OFFSET ${offset}
        """.query[FactRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToFact))

  /** ZLayer providing the live FactRepository. */
  val live: ZLayer[Any, Nothing, FactRepository] =
    ZLayer.succeed(new Live)
