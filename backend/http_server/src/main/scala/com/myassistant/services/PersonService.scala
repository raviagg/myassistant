package com.myassistant.services

import com.myassistant.db.repositories.PersonRepository
import com.myassistant.domain.{Person, CreatePerson, UpdatePerson}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for person management.
 *
 *  Validates inputs, enforces business rules, and delegates to PersonRepository
 *  for persistence.  All methods are pure ZIO effects with AppError in the
 *  error channel.
 */
trait PersonService:
  /** Create a new person, validating uniqueness of userIdentifier if set. */
  def createPerson(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person]

  /** Retrieve a person by id; fails with NotFound if absent. */
  def getPerson(id: UUID): ZIO[ZConnectionPool, AppError, Person]

  /** Search persons with optional filters. */
  def searchPersons(
      name:            Option[String],
      gender:          Option[String],
      dateOfBirth:     Option[java.time.LocalDate],
      dateOfBirthFrom: Option[java.time.LocalDate],
      dateOfBirthTo:   Option[java.time.LocalDate],
      householdId:     Option[UUID],
      limit:           Int,
      offset:          Int,
  ): ZIO[ZConnectionPool, AppError, List[Person]]

  /** Apply a partial update; fails with NotFound if absent. */
  def updatePerson(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Person]

  /** Delete a person; fails with NotFound or ReferentialIntegrityError. */
  def deletePerson(id: UUID): ZIO[ZConnectionPool, AppError, Unit]

object PersonService:

  /** Live implementation backed by PersonRepository. */
  final class Live(repo: PersonRepository) extends PersonService:

    /** Create a new person; the repository handles uniqueness constraints via DB unique index. */
    def createPerson(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person] =
      repo.create(req)

    /** Retrieve a person by id; fails with NotFound if the record does not exist. */
    def getPerson(id: UUID): ZIO[ZConnectionPool, AppError, Person] =
      repo.findById(id).flatMap:
        case Some(p) => ZIO.succeed(p)
        case None    => ZIO.fail(AppError.NotFound("person", id.toString))

    /** Search persons with optional filters. */
    def searchPersons(
        name:            Option[String],
        gender:          Option[String],
        dateOfBirth:     Option[java.time.LocalDate],
        dateOfBirthFrom: Option[java.time.LocalDate],
        dateOfBirthTo:   Option[java.time.LocalDate],
        householdId:     Option[UUID],
        limit:           Int,
        offset:          Int,
    ): ZIO[ZConnectionPool, AppError, List[Person]] =
      repo.search(name, gender, dateOfBirth, dateOfBirthFrom, dateOfBirthTo, householdId, limit, offset)

    /** Apply a partial update; fails with NotFound if no record matched. */
    def updatePerson(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Person] =
      repo.update(id, patch).flatMap:
        case Some(p) => ZIO.succeed(p)
        case None    => ZIO.fail(AppError.NotFound("person", id.toString))

    /** Delete a person; fails with NotFound if absent, passes through ReferentialIntegrityError. */
    def deletePerson(id: UUID): ZIO[ZConnectionPool, AppError, Unit] =
      repo.delete(id).flatMap:
        case true  => ZIO.unit
        case false => ZIO.fail(AppError.NotFound("person", id.toString))

  /** ZLayer providing the live PersonService. */
  val live: ZLayer[PersonRepository, Nothing, PersonService] =
    ZLayer.fromFunction(new Live(_))
