package com.myassistant.db.repositories

import com.myassistant.domain.{CreatePerson, Gender, Person, UpdatePerson}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `person` table. */
trait PersonRepository:
  /** Insert a new person and return the persisted record. */
  def create(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person]

  /** Fetch a person by primary key. */
  def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Person]]

  /** Fetch a person by their login identifier. */
  def findByUserIdentifier(identifier: String): ZIO[ZConnectionPool, AppError, Option[Person]]

  /** Search persons with optional filter parameters. */
  def search(
      name:           Option[String],
      gender:         Option[String],
      dateOfBirth:    Option[java.time.LocalDate],
      dateOfBirthFrom: Option[java.time.LocalDate],
      dateOfBirthTo:  Option[java.time.LocalDate],
      householdId:    Option[UUID],
      limit:          Int,
      offset:         Int,
  ): ZIO[ZConnectionPool, AppError, List[Person]]

  /** Apply a partial update to a person record. */
  def update(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Option[Person]]

  /** Delete a person record; fails with ReferentialIntegrityError if referenced. */
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean]

object PersonRepository:

  // ── Row type ─────────────────────────────────────────────────────────────
  // id, full_name, gender, date_of_birth, preferred_name, user_identifier,
  // created_at, updated_at
  private type PersonRow =
    (String, String, String, Option[java.sql.Date], Option[String], Option[String],
     java.sql.Timestamp, java.sql.Timestamp)

  // ── Shared column list for SELECT / RETURNING ─────────────────────────────
  private val personCols = SqlFragment(
    """id::text, full_name, gender::text, date_of_birth, preferred_name,
       user_identifier, created_at, updated_at"""
  )

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToPerson(row: PersonRow): Person =
    val (id, fullName, gender, dob, preferredName, userIdentifier, createdAt, updatedAt) = row
    Person(
      id             = UUID.fromString(id),
      fullName       = fullName,
      gender         = gender.toLowerCase match
        case "male"   => Gender.Male
        case "female" => Gender.Female
        case other    => throw new IllegalArgumentException(s"Unknown gender: $other"),
      dateOfBirth    = dob.map(_.toLocalDate),
      preferredName  = preferredName,
      userIdentifier = userIdentifier,
      createdAt      = createdAt.toInstant,
      updatedAt      = updatedAt.toInstant,
    )

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends PersonRepository:

    /** Insert a new person and return the persisted record. */
    def create(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person] =
      val id        = UUID.randomUUID()
      val genderStr = req.gender match
        case Gender.Male   => "male"
        case Gender.Female => "female"
      val dobSql: Option[java.sql.Date] = req.dateOfBirth.map(java.sql.Date.valueOf)
      val q = sql"INSERT INTO person(id, full_name, gender, date_of_birth, preferred_name, user_identifier) " ++
              sql"VALUES (${id.toString}::uuid, ${req.fullName}, ${genderStr}::gender_type, " ++
              sql"${dobSql}, ${req.preferredName}, ${req.userIdentifier}) " ++
              sql"RETURNING " ++ personCols
      transaction(q.query[PersonRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT person returned no row"))))
        .map(rowToPerson)

    /** Fetch a person by primary key. */
    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Person]] =
      val q = sql"SELECT " ++ personCols ++
              sql" FROM person WHERE id = ${id.toString}::uuid"
      transaction(q.query[PersonRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToPerson))

    /** Fetch a person by their login identifier. */
    def findByUserIdentifier(identifier: String): ZIO[ZConnectionPool, AppError, Option[Person]] =
      val q = sql"SELECT " ++ personCols ++
              sql" FROM person WHERE user_identifier = $identifier"
      transaction(q.query[PersonRow].selectOne)
        .mapError(mapSqlError)
        .map(_.map(rowToPerson))

    /** Search persons with optional filter parameters. */
    def search(
        name:            Option[String],
        gender:          Option[String],
        dateOfBirth:     Option[java.time.LocalDate],
        dateOfBirthFrom: Option[java.time.LocalDate],
        dateOfBirthTo:   Option[java.time.LocalDate],
        householdId:     Option[UUID],
        limit:           Int,
        offset:          Int,
    ): ZIO[ZConnectionPool, AppError, List[Person]] =
      val namePat = name.map(n => s"%$n%")
      val conds = List.concat(
        namePat.map(p => SqlFragment(s"(p.full_name ILIKE '${p.replace("'","''")}' OR p.preferred_name ILIKE '${p.replace("'","''")}' )")),
        gender.map(g  => sql"p.gender = ${g}::gender_type"),
        dateOfBirth.map(d    => sql"p.date_of_birth = ${java.sql.Date.valueOf(d)}"),
        dateOfBirthFrom.map(d => sql"p.date_of_birth >= ${java.sql.Date.valueOf(d)}"),
        dateOfBirthTo.map(d   => sql"p.date_of_birth <= ${java.sql.Date.valueOf(d)}"),
      )
      val (joinFrag, whereFrag) = householdId match
        case None =>
          val j = SqlFragment("")
          val w = conds match
            case Nil => SqlFragment("")
            case cs  => SqlFragment(" WHERE ") ++ cs.reduce(_ ++ SqlFragment(" AND ") ++ _)
          (j, w)
        case Some(hid) =>
          val j = sql" JOIN person_household ph ON ph.person_id = p.id AND ph.household_id = ${hid.toString}::uuid"
          val w = conds match
            case Nil => SqlFragment("")
            case cs  => SqlFragment(" WHERE ") ++ cs.reduce(_ ++ SqlFragment(" AND ") ++ _)
          (j, w)
      val q = sql"SELECT p.id::text, p.full_name, p.gender::text, p.date_of_birth, " ++
              sql"p.preferred_name, p.user_identifier, p.created_at, p.updated_at " ++
              sql"FROM person p" ++ joinFrag ++ whereFrag ++
              sql" ORDER BY p.full_name LIMIT ${limit} OFFSET ${offset}"
      transaction(q.query[PersonRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToPerson))

    /** Apply a partial update; only set non-None fields.
     *
     *  Builds a dynamic UPDATE via SqlFragment concatenation.
     *  Single-quote escaping protects free-text fields.
     */
    def update(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Option[Person]] =
      // Collect non-None assignments as SQL fragments
      val assignments: List[SqlFragment] = List.concat(
        patch.fullName.map(v =>
          SqlFragment(s"full_name = '${v.replace("'", "''")}'")
        ),
        patch.gender.map(g =>
          SqlFragment(s"gender = '${g match { case Gender.Male => "male"; case Gender.Female => "female" }}'::gender_type")
        ),
        patch.dateOfBirth.map(d =>
          SqlFragment(s"date_of_birth = '$d'")
        ),
        patch.preferredName.map(n =>
          SqlFragment(s"preferred_name = '${n.replace("'", "''")}'")
        ),
        patch.userIdentifier.map(u =>
          SqlFragment(s"user_identifier = '${u.replace("'", "''")}'")
        ),
      )
      if assignments.isEmpty then
        findById(id)
      else
        val setFrag = assignments.reduce(_ ++ SqlFragment(", ") ++ _)
        val q = sql"UPDATE person SET " ++ setFrag ++
                sql" WHERE id = ${id.toString}::uuid RETURNING " ++ personCols
        transaction(q.query[PersonRow].selectOne)
          .mapError(mapSqlError)
          .map(_.map(rowToPerson))

    /** Delete a person; checks for blocking FK references first. */
    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      for
        docCount <- transaction(
          sql"SELECT COUNT(*)::text FROM document WHERE person_id = ${id.toString}::uuid"
            .query[String].selectOne.map(_.flatMap(_.toIntOption).getOrElse(0))
        ).mapError(mapSqlError)
        relCount <- transaction(
          sql"SELECT COUNT(*)::text FROM relationship WHERE person_id_a = ${id.toString}::uuid OR person_id_b = ${id.toString}::uuid"
            .query[String].selectOne.map(_.flatMap(_.toIntOption).getOrElse(0))
        ).mapError(mapSqlError)
        phCount  <- transaction(
          sql"SELECT COUNT(*)::text FROM person_household WHERE person_id = ${id.toString}::uuid"
            .query[String].selectOne.map(_.flatMap(_.toIntOption).getOrElse(0))
        ).mapError(mapSqlError)
        blocking  = Map.empty[String, Int]
          ++ (if docCount > 0 then Map("document" -> docCount) else Map.empty)
          ++ (if relCount > 0 then Map("relationship" -> relCount) else Map.empty)
          ++ (if phCount  > 0 then Map("person_household" -> phCount) else Map.empty)
        _        <- ZIO.when(blocking.nonEmpty)(
                      ZIO.fail(AppError.ReferentialIntegrityError(
                        s"Cannot delete person $id: referenced by other records",
                        blocking
                      ))
                    )
        deleted  <- transaction(
          sql"DELETE FROM person WHERE id = ${id.toString}::uuid".delete
        ).mapError(mapSqlError)
          .map(_ > 0)
      yield deleted

  /** ZLayer providing the live PersonRepository. */
  val live: ZLayer[Any, Nothing, PersonRepository] =
    ZLayer.succeed(new Live)
