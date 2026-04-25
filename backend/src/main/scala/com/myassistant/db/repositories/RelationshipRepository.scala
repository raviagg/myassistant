package com.myassistant.db.repositories

import com.myassistant.domain.{CreateRelationship, Relationship, RelationType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `relationship` table. */
trait RelationshipRepository:
  /** Insert a new relationship. */
  def create(req: CreateRelationship): ZIO[ZConnectionPool, AppError, Relationship]

  /** Fetch a relationship by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Relationship]]

  /** Fetch all relationships where personId is either end. */
  def findByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[Relationship]]

  /** Delete a relationship by primary key. */
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

object RelationshipRepository:

  // ── Row type ──────────────────────────────────────────────────────────────
  // id, person_id_a, person_id_b, relation_type, created_at, updated_at
  private type RelRow =
    (String, String, String, String, java.sql.Timestamp, java.sql.Timestamp)

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToRelationship(row: RelRow): Relationship =
    val (id, personIdA, personIdB, relType, createdAt, updatedAt) = row
    Relationship(
      id           = UUID.fromString(id),
      fromPersonId = UUID.fromString(personIdA),
      toPersonId   = UUID.fromString(personIdB),
      relationType = relType.toLowerCase match
        case "father"   => RelationType.Father
        case "mother"   => RelationType.Mother
        case "son"      => RelationType.Son
        case "daughter" => RelationType.Daughter
        case "brother"  => RelationType.Brother
        case "sister"   => RelationType.Sister
        case "husband"  => RelationType.Husband
        case "wife"     => RelationType.Wife
        case other      => throw new IllegalArgumentException(s"Unknown relation_type: $other"),
      createdAt    = createdAt.toInstant,
      updatedAt    = updatedAt.toInstant,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends RelationshipRepository:

    /** Insert a new relationship and return the persisted record. */
    def create(req: CreateRelationship): ZIO[ZConnectionPool, AppError, Relationship] =
      val id      = UUID.randomUUID()
      val relStr  = req.relationType match
        case RelationType.Father   => "father"
        case RelationType.Mother   => "mother"
        case RelationType.Son      => "son"
        case RelationType.Daughter => "daughter"
        case RelationType.Brother  => "brother"
        case RelationType.Sister   => "sister"
        case RelationType.Husband  => "husband"
        case RelationType.Wife     => "wife"
      transaction {
        sql"""
          INSERT INTO relationship(id, person_id_a, person_id_b, relation_type)
          VALUES (${id.toString}::uuid,
                  ${req.fromPersonId.toString}::uuid,
                  ${req.toPersonId.toString}::uuid,
                  ${relStr}::relation_type)
          RETURNING id::text, person_id_a::text, person_id_b::text,
                    relation_type::text, created_at, updated_at
        """.query[RelRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT relationship returned no row"))))
        .map(rowToRelationship)

    /** Fetch a relationship by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Relationship]] =
      transaction {
        sql"""
          SELECT id::text, person_id_a::text, person_id_b::text,
                 relation_type::text, created_at, updated_at
          FROM relationship
          WHERE id = ${id.toString}::uuid
        """.query[RelRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToRelationship))

    /** Fetch all relationships where personId appears on either side, ordered by creation time. */
    def findByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[Relationship]] =
      transaction {
        sql"""
          SELECT id::text, person_id_a::text, person_id_b::text,
                 relation_type::text, created_at, updated_at
          FROM relationship
          WHERE person_id_a = ${personId.toString}::uuid
             OR person_id_b = ${personId.toString}::uuid
          ORDER BY created_at
        """.query[RelRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToRelationship))

    /** Delete a relationship by primary key; returns true if a row was deleted. */
    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction {
        sql"""DELETE FROM relationship WHERE id = ${id.toString}::uuid""".delete
      }.mapError(mapSqlError)
        .map(_ > 0)

  /** ZLayer providing the live RelationshipRepository. */
  val live: ZLayer[Any, Nothing, RelationshipRepository] =
    ZLayer.succeed(new Live)
