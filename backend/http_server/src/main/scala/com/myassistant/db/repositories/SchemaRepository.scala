package com.myassistant.db.repositories

import com.myassistant.domain.{EntityTypeSchema, ProposeEntityTypeSchema}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `entity_type_schema` table. */
trait SchemaRepository:
  /** Insert a new schema version. */
  def create(req: ProposeEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema]

  /** Fetch a schema by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]]

  /** Return the current active schema for a (domain, entityType) pair. */
  def findCurrent(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]]

  /** Return all schema versions for a (domain, entityType) pair. */
  def findAll(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]]

  /** List all current active schemas, optionally filtered by domain. */
  def listCurrent(domain: Option[String]): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]]

  /** Deactivate a schema version. */
  def deactivate(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

object SchemaRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, domain, entity_type, schema_version, description,
  // field_definitions (text), mandatory_fields (JSON array text),
  // extraction_prompt, is_active, change_description, created_at
  private type SchemaRow =
    (String, String, String, Int, String, String, String,
     String, Boolean, Option[String], java.sql.Timestamp)

  // ── Shared SELECT column fragment ─────────────────────────────────────────
  private val schemaCols = SqlFragment(
    """id::text, domain, entity_type, schema_version, description,
       field_definitions::text,
       array_to_json(mandatory_fields)::text,
       extraction_prompt, is_active, change_description, created_at"""
  )

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToSchema(row: SchemaRow): EntityTypeSchema =
    val (id, domain, entityType, schemaVersion, description, fieldDefsJson,
         mandatoryFieldsJson, extractionPrompt, isActive, changeDesc, createdAt) = row
    val fieldDefs       = circeParser.parse(fieldDefsJson).getOrElse(Json.arr())
    val mandatoryFields = circeParser.parse(mandatoryFieldsJson)
      .toOption
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.asString))
      .getOrElse(Nil)
    EntityTypeSchema(
      id                = UUID.fromString(id),
      domain            = domain,
      entityType        = entityType,
      schemaVersion     = schemaVersion,
      description       = description,
      fieldDefinitions  = fieldDefs,
      mandatoryFields   = mandatoryFields,
      extractionPrompt  = extractionPrompt,
      isActive          = isActive,
      changeDescription = changeDesc,
      createdAt         = createdAt.toInstant,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends SchemaRepository:

    /** Insert a new schema version; auto-increments version and deactivates the previous active version. */
    def create(req: ProposeEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      val id           = UUID.randomUUID()
      val fieldDefsStr = req.fieldDefinitions.noSpaces
      // The CTE deactivates the old version and the INSERT computes the new version number.
      val q = sql"""
        WITH prev_version AS (
          SELECT COALESCE(MAX(schema_version), 0) AS max_ver
          FROM entity_type_schema
          WHERE domain = ${req.domain} AND entity_type = ${req.entityType}
        ),
        deactivate_prev AS (
          UPDATE entity_type_schema
          SET is_active = false
          WHERE domain = ${req.domain}
            AND entity_type = ${req.entityType}
            AND is_active = true
        )
        INSERT INTO entity_type_schema(
          id, domain, entity_type, schema_version,
          description, field_definitions, extraction_prompt,
          is_active, change_description
        )
        SELECT
          ${id.toString}::uuid,
          ${req.domain},
          ${req.entityType},
          (SELECT max_ver + 1 FROM prev_version),
          ${req.description},
          ${fieldDefsStr}::jsonb,
          ${req.extractionPrompt},
          true,
          ${req.changeDescription}
        RETURNING """ ++ schemaCols
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT entity_type_schema returned no row"))))
        .map(rowToSchema)

    /** Fetch a schema record by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      val q = sql"SELECT " ++ schemaCols ++
              sql" FROM entity_type_schema WHERE id = ${id.toString}::uuid"
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToSchema))

    /** Return the current (highest active) schema for a (domain, entityType) pair. */
    def findCurrent(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      val q = sql"SELECT " ++ schemaCols ++
              sql" FROM current_entity_type_schema WHERE domain = $domain AND entity_type = $entityType"
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToSchema))

    /** Return all schema versions for a (domain, entityType) pair, newest first. */
    def findAll(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      val q = sql"SELECT " ++ schemaCols ++
              sql" FROM entity_type_schema WHERE domain = $domain AND entity_type = $entityType ORDER BY schema_version DESC"
      transaction(q.query[SchemaRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToSchema))

    /** List all current active schemas, optionally filtered by domain. */
    def listCurrent(domain: Option[String]): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      domain match
        case None =>
          val q = sql"SELECT " ++ schemaCols ++
                  sql" FROM current_entity_type_schema ORDER BY domain, entity_type"
          transaction(q.query[SchemaRow].selectAll)
            .mapError(mapSqlError)
            .map(_.toList.map(rowToSchema))
        case Some(d) =>
          val q = sql"SELECT " ++ schemaCols ++
                  sql" FROM current_entity_type_schema WHERE domain = $d ORDER BY entity_type"
          transaction(q.query[SchemaRow].selectAll)
            .mapError(mapSqlError)
            .map(_.toList.map(rowToSchema))

    /** Soft-delete a schema version by setting is_active = false; returns true if updated. */
    def deactivate(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction(
        sql"UPDATE entity_type_schema SET is_active = false WHERE id = ${id.toString}::uuid AND is_active = true".update
      ).mapError(mapSqlError)
        .map(_ > 0)

  /** ZLayer providing the live SchemaRepository. */
  val live: ZLayer[Any, Nothing, SchemaRepository] =
    ZLayer.succeed(new Live)
