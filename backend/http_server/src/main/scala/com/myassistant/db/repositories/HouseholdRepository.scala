package com.myassistant.db.repositories

import com.myassistant.domain.{CreateHousehold, Household, PersonHousehold, UpdateHousehold}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `household` and `person_household` tables. */
trait HouseholdRepository:
  /** Insert a new household. */
  def create(req: CreateHousehold): ZIO[ZConnectionPool, AppError, Household]

  /** Fetch a household by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Household]]

  /** Search households by name (case-insensitive partial match). */
  def searchByName(name: String): ZIO[ZConnectionPool, AppError, List[Household]]

  /** Apply a partial update to a household record. */
  def update(id: UUID, patch: UpdateHousehold): ZIO[ZConnectionPool, AppError, Option[Household]]

  /** Delete a household; fails with ReferentialIntegrityError if persons remain. */
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

  /** Add a person to a household. */
  def addMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, PersonHousehold]

  /** Remove a person from a household. */
  def removeMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, Boolean]

  /** List all members of a household. */
  def listMembers(householdId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]]

  /** List all household memberships for a person. */
  def listPersonHouseholds(personId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]]

object HouseholdRepository:

  // ── Row types ─────────────────────────────────────────────────────────────
  private type HouseholdRow       = (String, String, java.sql.Timestamp, java.sql.Timestamp)
  private type PersonHouseholdRow = (String, String, java.sql.Timestamp)

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToHousehold(row: HouseholdRow): Household =
    val (id, name, createdAt, updatedAt) = row
    Household(UUID.fromString(id), name, createdAt.toInstant, updatedAt.toInstant)

  private def rowToPersonHousehold(row: PersonHouseholdRow): PersonHousehold =
    val (personId, householdId, createdAt) = row
    PersonHousehold(UUID.fromString(personId), UUID.fromString(householdId), createdAt.toInstant)

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends HouseholdRepository:

    /** Insert a new household and return the persisted record. */
    def create(req: CreateHousehold): ZIO[ZConnectionPool, AppError, Household] =
      val id = UUID.randomUUID()
      transaction {
        sql"""
          INSERT INTO household(id, name)
          VALUES (${id.toString}::uuid, ${req.name})
          RETURNING id::text, name, created_at, updated_at
        """.query[HouseholdRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT household returned no row"))))
        .map(rowToHousehold)

    /** Fetch a household by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Household]] =
      transaction {
        sql"""
          SELECT id::text, name, created_at, updated_at
          FROM household
          WHERE id = ${id.toString}::uuid
        """.query[HouseholdRow].selectOne
      }.mapError(mapSqlError)
        .map(_.map(rowToHousehold))

    /** Search households by name (case-insensitive partial match). */
    def searchByName(name: String): ZIO[ZConnectionPool, AppError, List[Household]] =
      val pattern = s"%$name%"
      transaction {
        sql"""
          SELECT id::text, name, created_at, updated_at
          FROM household
          WHERE name ILIKE $pattern
          ORDER BY name
        """.query[HouseholdRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToHousehold))

    /** Apply a partial update to a household record. */
    def update(id: UUID, patch: UpdateHousehold): ZIO[ZConnectionPool, AppError, Option[Household]] =
      patch.name match
        case None =>
          findById(id)
        case Some(newName) =>
          transaction {
            sql"""
              UPDATE household
              SET name = $newName
              WHERE id = ${id.toString}::uuid
              RETURNING id::text, name, created_at, updated_at
            """.query[HouseholdRow].selectOne
          }.mapError(mapSqlError)
            .map(_.map(rowToHousehold))

    /** Delete a household; checks for blocking FK references first. */
    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      for
        docCount <- transaction {
          sql"""SELECT COUNT(*)::text FROM document WHERE household_id = ${id.toString}::uuid"""
            .query[String].selectOne.map(_.flatMap(_.toIntOption).getOrElse(0))
        }.mapError(mapSqlError)
        phCount  <- transaction {
          sql"""SELECT COUNT(*)::text FROM person_household WHERE household_id = ${id.toString}::uuid"""
            .query[String].selectOne.map(_.flatMap(_.toIntOption).getOrElse(0))
        }.mapError(mapSqlError)
        blocking  = Map.empty[String, Int]
          ++ (if docCount > 0 then Map("document" -> docCount) else Map.empty)
          ++ (if phCount  > 0 then Map("person_household" -> phCount) else Map.empty)
        _        <- ZIO.when(blocking.nonEmpty)(
                      ZIO.fail(AppError.ReferentialIntegrityError(
                        s"Cannot delete household $id: referenced by other records",
                        blocking
                      ))
                    )
        deleted  <- transaction {
          sql"""DELETE FROM household WHERE id = ${id.toString}::uuid""".delete
        }.mapError(mapSqlError)
          .map(_ > 0)
      yield deleted

    /** Add a person to a household; idempotent via ON CONFLICT DO NOTHING. */
    def addMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, PersonHousehold] =
      transaction {
        sql"""
          INSERT INTO person_household(person_id, household_id)
          VALUES (${personId.toString}::uuid, ${householdId.toString}::uuid)
          ON CONFLICT (person_id, household_id) DO NOTHING
          RETURNING person_id::text, household_id::text, created_at
        """.query[PersonHouseholdRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.Conflict(s"Person $personId is already a member of household $householdId")))
        .map(rowToPersonHousehold)

    /** Remove a person from a household. */
    def removeMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      transaction {
        sql"""
          DELETE FROM person_household
          WHERE person_id    = ${personId.toString}::uuid
            AND household_id = ${householdId.toString}::uuid
        """.delete
      }.mapError(mapSqlError)
        .map(_ > 0)

    /** List all member records for a household, ordered by join date. */
    def listMembers(householdId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]] =
      transaction {
        sql"""
          SELECT person_id::text, household_id::text, created_at
          FROM person_household
          WHERE household_id = ${householdId.toString}::uuid
          ORDER BY created_at
        """.query[PersonHouseholdRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToPersonHousehold))

    /** List all household memberships for a person, ordered by join date. */
    def listPersonHouseholds(personId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]] =
      transaction {
        sql"""
          SELECT person_id::text, household_id::text, created_at
          FROM person_household
          WHERE person_id = ${personId.toString}::uuid
          ORDER BY created_at
        """.query[PersonHouseholdRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToPersonHousehold))

  /** ZLayer providing the live HouseholdRepository. */
  val live: ZLayer[Any, Nothing, HouseholdRepository] =
    ZLayer.succeed(new Live)
