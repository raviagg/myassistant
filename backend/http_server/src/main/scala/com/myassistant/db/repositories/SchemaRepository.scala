package com.myassistant.db.repositories

import com.myassistant.domain.{CreateEntityTypeSchema, CreateSchemaVersion, EntityTypeSchema}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

trait SchemaRepository:
  def create(req: CreateEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema]
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]]
  def findCurrent(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]]
  def addVersion(domainId: UUID, entityType: String, req: CreateSchemaVersion): ZIO[ZConnectionPool, AppError, EntityTypeSchema]
  def listSchemas(domainId: Option[UUID], entityType: Option[String], activeOnly: Boolean): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]]
  def deactivate(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Boolean]

object SchemaRepository:

  // id, domain_id (text), entity_type, schema_version, description, field_definitions (text),
  // mandatory_fields (json text), is_active, created_at, updated_at
  private type SchemaRow =
    (String, String, String, Int, Option[String], String, String,
     Boolean, java.sql.Timestamp, java.sql.Timestamp)

  private val schemaCols = SqlFragment(
    """id::text, domain_id::text, entity_type, schema_version, description,
       field_definitions::text,
       array_to_json(mandatory_fields)::text,
       is_active, created_at, updated_at"""
  )

  private def rowToSchema(row: SchemaRow): EntityTypeSchema =
    val (id, domainId, entityType, schemaVersion, description, fieldDefsJson,
         mandatoryFieldsJson, isActive, createdAt, updatedAt) = row
    val fieldDefs       = circeParser.parse(fieldDefsJson).getOrElse(Json.arr())
    val mandatoryFields = circeParser.parse(mandatoryFieldsJson)
      .toOption
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.asString))
      .getOrElse(Nil)
    EntityTypeSchema(
      id               = UUID.fromString(id),
      domainId         = UUID.fromString(domainId),
      entityType       = entityType,
      schemaVersion    = schemaVersion,
      description      = description,
      fieldDefinitions = fieldDefs,
      mandatoryFields  = mandatoryFields,
      isActive         = isActive,
      createdAt        = createdAt.toInstant,
      updatedAt        = updatedAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  final class Live extends SchemaRepository:

    def create(req: CreateEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      val id           = UUID.randomUUID()
      val fieldDefsStr = req.fieldDefinitions.noSpaces
      val q = sql"""
        WITH deactivate_prev AS (
          UPDATE entity_type_schema
          SET is_active = false
          WHERE domain_id = ${req.domainId.toString}::uuid
            AND entity_type = ${req.entityType}
            AND is_active = true
        )
        INSERT INTO entity_type_schema(
          id, domain_id, entity_type, schema_version,
          description, field_definitions, is_active
        )
        VALUES (
          ${id.toString}::uuid,
          ${req.domainId.toString}::uuid,
          ${req.entityType},
          1,
          ${req.description},
          ${fieldDefsStr}::jsonb,
          true
        )
        RETURNING """ ++ schemaCols
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT entity_type_schema returned no row"))))
        .map(rowToSchema)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      val q = sql"SELECT " ++ schemaCols ++
              sql" FROM entity_type_schema WHERE id = ${id.toString}::uuid"
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToSchema))

    def findCurrent(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      val q = sql"SELECT " ++ schemaCols ++
              sql" FROM current_entity_type_schema WHERE domain_id = ${domainId.toString}::uuid AND entity_type = $entityType"
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToSchema))

    def addVersion(domainId: UUID, entityType: String, req: CreateSchemaVersion): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      val id           = UUID.randomUUID()
      val fieldDefsStr = req.fieldDefinitions.noSpaces
      val q = sql"""
        WITH prev_version AS (
          SELECT COALESCE(MAX(schema_version), 0) AS max_ver
          FROM entity_type_schema
          WHERE domain_id = ${domainId.toString}::uuid AND entity_type = $entityType
        ),
        deactivate_prev AS (
          UPDATE entity_type_schema
          SET is_active = false
          WHERE domain_id = ${domainId.toString}::uuid
            AND entity_type = $entityType
            AND is_active = true
        )
        INSERT INTO entity_type_schema(
          id, domain_id, entity_type, schema_version,
          description, field_definitions, is_active
        )
        SELECT
          ${id.toString}::uuid,
          ${domainId.toString}::uuid,
          $entityType,
          (SELECT max_ver + 1 FROM prev_version),
          ${req.description},
          ${fieldDefsStr}::jsonb,
          true
        RETURNING """ ++ schemaCols
      transaction(q.query[SchemaRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT entity_type_schema version returned no row"))))
        .map(rowToSchema)

    def listSchemas(
        domainId:   Option[UUID],
        entityType: Option[String],
        activeOnly: Boolean,
    ): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      val conds = List.concat(
        domainId.map(v   => sql"domain_id = ${v.toString}::uuid"),
        entityType.map(v => sql"entity_type = $v"),
        if activeOnly then List(sql"is_active = true") else Nil,
      )
      val where = conds match
        case Nil   => SqlFragment("")
        case cs    =>
          val joined = cs.reduce(_ ++ SqlFragment(" AND ") ++ _)
          SqlFragment(" WHERE ") ++ joined
      val q = sql"SELECT " ++ schemaCols ++ sql" FROM entity_type_schema" ++ where ++
              sql" ORDER BY domain_id, entity_type, schema_version DESC"
      transaction(q.query[SchemaRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToSchema))

    def deactivate(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction(
        sql"""UPDATE entity_type_schema
              SET is_active = false
              WHERE domain_id = ${domainId.toString}::uuid
                AND entity_type = $entityType
                AND is_active = true""".update
      ).mapError(mapSqlError)
        .map(_ > 0)

  val live: ZLayer[Any, Nothing, SchemaRepository] =
    ZLayer.succeed(new Live)
