package com.myassistant.db.repositories

import com.myassistant.domain.{CreateFact, CurrentFact, Fact, OperationType}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

trait FactRepository:
  def create(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact]
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Fact]]
  def findByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]
  def findByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]
  def findCurrentByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, Option[CurrentFact]]
  def listCurrent(
      personId:    Option[UUID],
      householdId: Option[UUID],
      domainId:    Option[UUID],
      entityType:  Option[String],
      limit:       Int,
      offset:      Int,
  ): ZIO[ZConnectionPool, AppError, List[CurrentFact]]
  def countCurrent(
      personId:    Option[UUID],
      householdId: Option[UUID],
      domainId:    Option[UUID],
      entityType:  Option[String],
  ): ZIO[ZConnectionPool, AppError, Long]
  def searchCurrentBySimilarity(
      embedding:           List[Double],
      personId:            Option[UUID],
      householdId:         Option[UUID],
      domainId:            Option[UUID],
      entityType:          Option[String],
      limit:               Int,
      similarityThreshold: Double,
  ): ZIO[ZConnectionPool, AppError, List[(CurrentFact, Double)]]

object FactRepository:

  private type FactRow =
    (String, String, String, String, String, String, java.sql.Timestamp)

  private type CurrentFactRow =
    (String, String, Option[String], Option[String], String, java.sql.Timestamp)

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
      fields    = circeParser.parse(fieldsJson).getOrElse(Json.obj()),
      createdAt = createdAt.toInstant,
    )

  private def rowToCurrentFact(row: CurrentFactRow): CurrentFact =
    val (entityInstanceId, schemaId, personId, householdId, fieldsJson, lastUpdatedAt) = row
    CurrentFact(
      entityInstanceId = UUID.fromString(entityInstanceId),
      schemaId         = UUID.fromString(schemaId),
      personId         = personId.map(UUID.fromString),
      householdId      = householdId.map(UUID.fromString),
      fields           = circeParser.parse(fieldsJson).getOrElse(Json.obj()),
      lastUpdatedAt    = lastUpdatedAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  // CTE that computes current (non-deleted) entity states with person/household context
  private def currentFactsCte(
      personId:    Option[UUID],
      householdId: Option[UUID],
      domainId:    Option[UUID],
      entityType:  Option[String],
  ): SqlFragment =
    val conds = List.concat(
      personId.map(v    => sql"d.person_id = ${v.toString}::uuid"),
      householdId.map(v => sql"d.household_id = ${v.toString}::uuid"),
      domainId.map(v    => sql"ets.domain_id = ${v.toString}::uuid"),
      entityType.map(v  => sql"ets.entity_type = $v"),
    )
    val filterWhere = conds match
      case Nil   => SqlFragment("AND 1=1")
      case cs    => cs.map(c => SqlFragment("AND ") ++ c).reduce(_ ++ SqlFragment(" ") ++ _)
    SqlFragment("""
      WITH last_ops AS (
        SELECT DISTINCT ON (f.entity_instance_id)
          f.entity_instance_id,
          f.schema_id,
          f.operation_type,
          f.fields,
          f.created_at AS last_updated_at,
          d.person_id,
          d.household_id
        FROM fact f
        JOIN document d ON d.id = f.document_id
        JOIN entity_type_schema ets ON ets.id = f.schema_id
        WHERE 1=1
      """) ++ filterWhere ++ SqlFragment("""
        ORDER BY f.entity_instance_id, f.created_at DESC
      )
      SELECT
        entity_instance_id::text,
        schema_id::text,
        person_id::text,
        household_id::text,
        fields::text,
        last_updated_at
      FROM last_ops
      WHERE operation_type != 'delete'
    """)

  final class Live extends FactRepository:

    def create(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact] =
      val id          = UUID.randomUUID()
      val opStr       = req.operationType match
        case OperationType.Create => "create"
        case OperationType.Update => "update"
        case OperationType.Delete => "delete"
      val fieldsStr   = req.fields.noSpaces
      val embeddingStr = req.embedding.mkString("[", ",", "]")
      transaction {
        sql"""
          INSERT INTO fact(id, document_id, schema_id, entity_instance_id, operation_type, fields, embedding)
          VALUES (
            ${id.toString}::uuid,
            ${req.documentId.toString}::uuid,
            ${req.schemaId.toString}::uuid,
            ${req.entityInstanceId.toString}::uuid,
            ${opStr}::operation_type,
            ${fieldsStr}::jsonb,
            ${embeddingStr}::vector
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

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text, document_id::text, schema_id::text, entity_instance_id::text,
            operation_type::text, fields::text, created_at
          FROM fact
          WHERE id = ${id.toString}::uuid
        """.query[FactRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToFact))

    def findByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text, document_id::text, schema_id::text, entity_instance_id::text,
            operation_type::text, fields::text, created_at
          FROM fact
          WHERE entity_instance_id = ${entityInstanceId.toString}::uuid
          ORDER BY created_at ASC
        """.query[FactRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToFact))

    def findByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      transaction {
        sql"""
          SELECT
            id::text, document_id::text, schema_id::text, entity_instance_id::text,
            operation_type::text, fields::text, created_at
          FROM fact
          WHERE document_id = ${documentId.toString}::uuid
          ORDER BY created_at ASC
        """.query[FactRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToFact))

    def findCurrentByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, Option[CurrentFact]] =
      transaction {
        sql"""
          WITH last_op AS (
            SELECT DISTINCT ON (f.entity_instance_id)
              f.entity_instance_id,
              f.schema_id,
              f.operation_type,
              f.fields,
              f.created_at AS last_updated_at,
              d.person_id,
              d.household_id
            FROM fact f
            JOIN document d ON d.id = f.document_id
            WHERE f.entity_instance_id = ${entityInstanceId.toString}::uuid
            ORDER BY f.entity_instance_id, f.created_at DESC
          )
          SELECT
            entity_instance_id::text,
            schema_id::text,
            person_id::text,
            household_id::text,
            fields::text,
            last_updated_at
          FROM last_op
          WHERE operation_type != 'delete'
        """.query[CurrentFactRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToCurrentFact))

    def listCurrent(
        personId:    Option[UUID],
        householdId: Option[UUID],
        domainId:    Option[UUID],
        entityType:  Option[String],
        limit:       Int,
        offset:      Int,
    ): ZIO[ZConnectionPool, AppError, List[CurrentFact]] =
      val cte = currentFactsCte(personId, householdId, domainId, entityType)
      val q = cte ++ sql" ORDER BY last_updated_at DESC LIMIT ${limit} OFFSET ${offset}"
      transaction(q.query[CurrentFactRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToCurrentFact))

    def countCurrent(
        personId:    Option[UUID],
        householdId: Option[UUID],
        domainId:    Option[UUID],
        entityType:  Option[String],
    ): ZIO[ZConnectionPool, AppError, Long] =
      val cte = currentFactsCte(personId, householdId, domainId, entityType)
      val q   = SqlFragment("SELECT COUNT(*) FROM (") ++ cte ++ SqlFragment(") sub")
      transaction(q.query[Long].selectOne.map(_.getOrElse(0L)))
        .mapError(mapSqlError)

    def searchCurrentBySimilarity(
        embedding:           List[Double],
        personId:            Option[UUID],
        householdId:         Option[UUID],
        domainId:            Option[UUID],
        entityType:          Option[String],
        limit:               Int,
        similarityThreshold: Double,
    ): ZIO[ZConnectionPool, AppError, List[(CurrentFact, Double)]] =
      val embStr = embedding.mkString("[", ",", "]")
      val conds = List.concat(
        personId.map(v    => sql"d.person_id = ${v.toString}::uuid"),
        householdId.map(v => sql"d.household_id = ${v.toString}::uuid"),
        domainId.map(v    => sql"ets.domain_id = ${v.toString}::uuid"),
        entityType.map(v  => sql"ets.entity_type = $v"),
      )
      val filterWhere = conds match
        case Nil => SqlFragment("AND 1=1")
        case cs  => cs.map(c => SqlFragment("AND ") ++ c).reduce(_ ++ SqlFragment(" ") ++ _)
      type SimRow = (String, String, Option[String], Option[String], String, java.sql.Timestamp, String)
      val q = SqlFragment(s"""
        WITH last_ops AS (
          SELECT DISTINCT ON (f.entity_instance_id)
            f.entity_instance_id,
            f.schema_id,
            f.operation_type,
            f.fields,
            f.created_at AS last_updated_at,
            f.embedding,
            d.person_id,
            d.household_id
          FROM fact f
          JOIN document d ON d.id = f.document_id
          JOIN entity_type_schema ets ON ets.id = f.schema_id
          WHERE 1=1
      """) ++ filterWhere ++ SqlFragment(s"""
          ORDER BY f.entity_instance_id, f.created_at DESC
        )
        SELECT
          entity_instance_id::text,
          schema_id::text,
          person_id::text,
          household_id::text,
          fields::text,
          last_updated_at,
          (1 - (embedding <=> '$embStr'::vector))::text AS similarity_score
        FROM last_ops
        WHERE operation_type != 'delete'
          AND (1 - (embedding <=> '$embStr'::vector)) >= $similarityThreshold
        ORDER BY embedding <=> '$embStr'::vector
        LIMIT $limit
      """)
      transaction(q.query[SimRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map { row =>
          val (eiId, schId, pId, hId, fj, ts, sim) = row
          val cf = rowToCurrentFact((eiId, schId, pId, hId, fj, ts))
          (cf, sim.toDoubleOption.getOrElse(0.0))
        })

  val live: ZLayer[Any, Nothing, FactRepository] =
    ZLayer.succeed(new Live)
