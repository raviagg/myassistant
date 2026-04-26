package com.myassistant.db.repositories

import com.myassistant.domain.{CreateDocument, Document}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `document` table. */
trait DocumentRepository:
  /** Insert a new document. */
  def create(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document]

  /** Fetch a document by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Document]]

  /** List documents for a person or household with optional source-type filter. */
  def list(
      personId:    Option[UUID],
      householdId: Option[UUID],
      sourceType:  Option[String],
      limit:       Int,
      offset:      Int,
  ): ZIO[ZConnectionPool, AppError, List[Document]]

  /** Return the count of documents matching the given filters. */
  def count(
      personId:    Option[UUID],
      householdId: Option[UUID],
      sourceType:  Option[String],
  ): ZIO[ZConnectionPool, AppError, Long]

object DocumentRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, person_id, household_id, content_text, source_type,
  // files (as text), supersedes_ids (as JSON array text), created_at
  private type DocRow =
    (String, Option[String], Option[String], String, String,
     String, String, java.sql.Timestamp)

  // ── Shared column list ────────────────────────────────────────────────────
  private val docCols = SqlFragment(
    """id::text, person_id::text, household_id::text, content_text, source_type,
       files::text, array_to_json(supersedes_ids)::text, created_at"""
  )

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToDocument(row: DocRow): Document =
    val (id, personId, householdId, contentText, sourceType, filesJson, supersedesJson, createdAt) = row
    val files         = circeParser.parse(filesJson).getOrElse(Json.arr())
    val supersedesIds = circeParser.parse(supersedesJson)
      .toOption
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.asString).map(UUID.fromString))
      .getOrElse(Nil)
    Document(
      id            = UUID.fromString(id),
      personId      = personId.map(UUID.fromString),
      householdId   = householdId.map(UUID.fromString),
      contentText   = contentText,
      sourceType    = sourceType,
      files         = files,
      supersedesIds = supersedesIds,
      createdAt     = createdAt.toInstant,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  // ── WHERE-clause builder ──────────────────────────────────────────────────
  /** Build an optional WHERE fragment from filter values. */
  private def whereClause(
      personId:    Option[UUID],
      householdId: Option[UUID],
      sourceType:  Option[String],
  ): SqlFragment =
    val conditions = List.concat(
      personId.map(v    => sql"person_id = ${v.toString}::uuid"),
      householdId.map(v => sql"household_id = ${v.toString}::uuid"),
      sourceType.map(v  => sql"source_type = $v"),
    )
    conditions match
      case Nil  => SqlFragment("")
      case conds =>
        val joined = conds.reduce(_ ++ SqlFragment(" AND ") ++ _)
        SqlFragment(" WHERE ") ++ joined

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends DocumentRepository:

    /** Insert a new document and return the persisted record. */
    def create(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document] =
      val id            = UUID.randomUUID()
      val filesStr      = req.files.noSpaces
      // Build a Postgres array literal for supersedes_ids
      val supersedesLit =
        if req.supersedesIds.isEmpty then SqlFragment("ARRAY[]::uuid[]")
        else SqlFragment(
          s"ARRAY[${req.supersedesIds.map(u => s"'$u'::uuid").mkString(",")}]"
        )
      val q = sql"INSERT INTO document(id, person_id, household_id, content_text, source_type, files, supersedes_ids) " ++
              sql"VALUES (${id.toString}::uuid, ${req.personId.map(_.toString)}::uuid, ${req.householdId.map(_.toString)}::uuid, " ++
              sql"${req.contentText}, ${req.sourceType}, ${filesStr}::jsonb, " ++
              supersedesLit ++
              sql") RETURNING " ++ docCols
      transaction(q.query[DocRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT document returned no row"))))
        .map(rowToDocument)

    /** Fetch a document by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Document]] =
      val q = sql"SELECT " ++ docCols ++
              sql" FROM document WHERE id = ${id.toString}::uuid"
      transaction(q.query[DocRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToDocument))

    /** List documents with optional filters; newest first. */
    def list(
        personId:    Option[UUID],
        householdId: Option[UUID],
        sourceType:  Option[String],
        limit:       Int,
        offset:      Int,
    ): ZIO[ZConnectionPool, AppError, List[Document]] =
      val where = whereClause(personId, householdId, sourceType)
      val q = sql"SELECT " ++ docCols ++ sql" FROM document" ++ where ++
              sql" ORDER BY created_at DESC LIMIT ${limit} OFFSET ${offset}"
      transaction(q.query[DocRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToDocument))

    /** Count documents matching optional filters. */
    def count(
        personId:    Option[UUID],
        householdId: Option[UUID],
        sourceType:  Option[String],
    ): ZIO[ZConnectionPool, AppError, Long] =
      val where = whereClause(personId, householdId, sourceType)
      val q = sql"SELECT COUNT(*)::text FROM document" ++ where
      transaction(q.query[String].selectOne.map(_.flatMap(_.toLongOption).getOrElse(0L)))
        .mapError(mapSqlError)

  /** ZLayer providing the live DocumentRepository. */
  val live: ZLayer[Any, Nothing, DocumentRepository] =
    ZLayer.succeed(new Live)
