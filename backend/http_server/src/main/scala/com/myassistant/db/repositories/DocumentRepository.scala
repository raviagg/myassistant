package com.myassistant.db.repositories

import com.myassistant.domain.{CreateDocument, Document}
import com.myassistant.errors.AppError
import io.circe.Json
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

trait DocumentRepository:
  def create(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document]
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Document]]
  def list(
      personId:     Option[UUID],
      householdId:  Option[UUID],
      sourceTypeId: Option[UUID],
      createdAfter:  Option[String],
      createdBefore: Option[String],
      limit:        Int,
      offset:       Int,
  ): ZIO[ZConnectionPool, AppError, List[Document]]
  def count(
      personId:     Option[UUID],
      householdId:  Option[UUID],
      sourceTypeId: Option[UUID],
  ): ZIO[ZConnectionPool, AppError, Long]
  def searchBySimilarity(
      embedding:           List[Double],
      personId:            Option[UUID],
      householdId:         Option[UUID],
      sourceTypeId:        Option[UUID],
      limit:               Int,
      similarityThreshold: Double,
  ): ZIO[ZConnectionPool, AppError, List[(Document, Double)]]

object DocumentRepository:

  // id, person_id, household_id, content_text, source_type_id (text), files (text), supersedes_ids (text), created_at
  private type DocRow =
    (String, Option[String], Option[String], String, String,
     String, String, java.sql.Timestamp)

  private val docCols = SqlFragment(
    """id::text, person_id::text, household_id::text, content_text, source_type_id::text,
       files::text, array_to_json(supersedes_ids)::text, created_at"""
  )

  private def rowToDocument(row: DocRow): Document =
    val (id, personId, householdId, contentText, sourceTypeId, filesJson, supersedesJson, createdAt) = row
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
      sourceTypeId  = UUID.fromString(sourceTypeId),
      files         = files,
      supersedesIds = supersedesIds,
      createdAt     = createdAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  private def whereClause(
      personId:     Option[UUID],
      householdId:  Option[UUID],
      sourceTypeId: Option[UUID],
  ): SqlFragment =
    val conditions = List.concat(
      personId.map(v     => sql"person_id = ${v.toString}::uuid"),
      householdId.map(v  => sql"household_id = ${v.toString}::uuid"),
      sourceTypeId.map(v => sql"source_type_id = ${v.toString}::uuid"),
    )
    conditions match
      case Nil   => SqlFragment("")
      case conds =>
        val joined = conds.reduce(_ ++ SqlFragment(" AND ") ++ _)
        SqlFragment(" WHERE ") ++ joined

  final class Live extends DocumentRepository:

    def create(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document] =
      val id            = UUID.randomUUID()
      val filesStr      = req.files.noSpaces
      val embeddingStr  = req.embedding.mkString("[", ",", "]")
      val supersedesLit =
        if req.supersedesIds.isEmpty then SqlFragment("ARRAY[]::uuid[]")
        else SqlFragment(
          s"ARRAY[${req.supersedesIds.map(u => s"'$u'::uuid").mkString(",")}]"
        )
      val q = sql"INSERT INTO document(id, person_id, household_id, content_text, source_type_id, files, supersedes_ids, embedding) " ++
              sql"VALUES (${id.toString}::uuid, ${req.personId.map(_.toString)}::uuid, ${req.householdId.map(_.toString)}::uuid, " ++
              sql"${req.contentText}, ${req.sourceTypeId.toString}::uuid, ${filesStr}::jsonb, " ++
              supersedesLit ++
              SqlFragment(s", '$embeddingStr'::vector") ++
              sql") RETURNING " ++ docCols
      transaction(q.query[DocRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT document returned no row"))))
        .map(rowToDocument)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Document]] =
      val q = sql"SELECT " ++ docCols ++
              sql" FROM document WHERE id = ${id.toString}::uuid"
      transaction(q.query[DocRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToDocument))

    def list(
        personId:      Option[UUID],
        householdId:   Option[UUID],
        sourceTypeId:  Option[UUID],
        createdAfter:  Option[String],
        createdBefore: Option[String],
        limit:         Int,
        offset:        Int,
    ): ZIO[ZConnectionPool, AppError, List[Document]] =
      val baseConds = List.concat(
        personId.map(v     => sql"person_id = ${v.toString}::uuid"),
        householdId.map(v  => sql"household_id = ${v.toString}::uuid"),
        sourceTypeId.map(v => sql"source_type_id = ${v.toString}::uuid"),
        createdAfter.map(v  => sql"created_at > ${v}::timestamptz"),
        createdBefore.map(v => sql"created_at < ${v}::timestamptz"),
      )
      val where = baseConds match
        case Nil   => SqlFragment("")
        case conds =>
          val joined = conds.reduce(_ ++ SqlFragment(" AND ") ++ _)
          SqlFragment(" WHERE ") ++ joined
      val q = sql"SELECT " ++ docCols ++ sql" FROM document" ++ where ++
              sql" ORDER BY created_at DESC LIMIT ${limit} OFFSET ${offset}"
      transaction(q.query[DocRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToDocument))

    def count(
        personId:     Option[UUID],
        householdId:  Option[UUID],
        sourceTypeId: Option[UUID],
    ): ZIO[ZConnectionPool, AppError, Long] =
      val where = whereClause(personId, householdId, sourceTypeId)
      val q = sql"SELECT COUNT(*)::text FROM document" ++ where
      transaction(q.query[String].selectOne.map(_.flatMap(_.toLongOption).getOrElse(0L)))
        .mapError(mapSqlError)

    def searchBySimilarity(
        embedding:           List[Double],
        personId:            Option[UUID],
        householdId:         Option[UUID],
        sourceTypeId:        Option[UUID],
        limit:               Int,
        similarityThreshold: Double,
    ): ZIO[ZConnectionPool, AppError, List[(Document, Double)]] =
      val embStr = embedding.mkString("[", ",", "]")
      val conds = List.concat(
        personId.map(v     => sql"person_id = ${v.toString}::uuid"),
        householdId.map(v  => sql"household_id = ${v.toString}::uuid"),
        sourceTypeId.map(v => sql"source_type_id = ${v.toString}::uuid"),
      )
      val baseWhere = conds match
        case Nil   => SqlFragment("")
        case cs    =>
          val joined = cs.reduce(_ ++ SqlFragment(" AND ") ++ _)
          SqlFragment(" WHERE ") ++ joined
      // DocRow + similarity score (Double stored as String)
      type DocSimRow = (String, Option[String], Option[String], String, String, String, String, java.sql.Timestamp, String)
      val q = SqlFragment(
        s"""SELECT id::text, person_id::text, household_id::text, content_text, source_type_id::text,
                   files::text, array_to_json(supersedes_ids)::text, created_at,
                   (1 - (embedding <=> '$embStr'::vector))::text AS similarity_score
            FROM document"""
      ) ++ baseWhere ++
        SqlFragment(s" AND (1 - (embedding <=> '$embStr'::vector)) >= $similarityThreshold") ++
        SqlFragment(s" ORDER BY embedding <=> '$embStr'::vector LIMIT $limit")
      transaction(q.query[DocSimRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map { row =>
          val (id, pid, hid, ct, stId, fj, sj, ca, sim) = row
          val doc = rowToDocument((id, pid, hid, ct, stId, fj, sj, ca))
          (doc, sim.toDoubleOption.getOrElse(0.0))
        })

  val live: ZLayer[Any, Nothing, DocumentRepository] =
    ZLayer.succeed(new Live)
