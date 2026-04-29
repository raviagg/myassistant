package com.myassistant.services

import com.myassistant.db.repositories.HouseholdRepository
import com.myassistant.domain.{Household, CreateHousehold, UpdateHousehold, PersonHousehold}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for household management. */
trait HouseholdService:
  /** Create a new household. */
  def createHousehold(req: CreateHousehold): ZIO[ZConnectionPool, AppError, Household]

  /** Retrieve a household by id; fails with NotFound if absent. */
  def getHousehold(id: UUID): ZIO[ZConnectionPool, AppError, Household]

  /** Search households by name (case-insensitive partial match). */
  def searchHouseholds(name: String): ZIO[ZConnectionPool, AppError, List[Household]]

  /** Apply a partial update; fails with NotFound if absent. */
  def updateHousehold(id: UUID, patch: UpdateHousehold): ZIO[ZConnectionPool, AppError, Household]

  /** Delete a household; fails with ReferentialIntegrityError if non-empty. */
  def deleteHousehold(id: UUID): ZIO[ZConnectionPool, AppError, Unit]

  /** Add a person to a household. */
  def addMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, PersonHousehold]

  /** Remove a person from a household. */
  def removeMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, Unit]

  /** List all members of a household. */
  def listMembers(householdId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]]

  /** List all household memberships for a person. */
  def listPersonHouseholds(personId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]]

object HouseholdService:

  /** Live implementation backed by HouseholdRepository. */
  final class Live(repo: HouseholdRepository) extends HouseholdService:

    /** Create a new household; the repository handles constraint violations. */
    def createHousehold(req: CreateHousehold): ZIO[ZConnectionPool, AppError, Household] =
      repo.create(req)

    /** Retrieve a household by id; fails with NotFound if the record does not exist. */
    def getHousehold(id: UUID): ZIO[ZConnectionPool, AppError, Household] =
      repo.findById(id).flatMap:
        case Some(h) => ZIO.succeed(h)
        case None    => ZIO.fail(AppError.NotFound("household", id.toString))

    /** Search households by name. */
    def searchHouseholds(name: String): ZIO[ZConnectionPool, AppError, List[Household]] =
      repo.searchByName(name)

    /** Apply a partial update; fails with NotFound if no record matched. */
    def updateHousehold(id: UUID, patch: UpdateHousehold): ZIO[ZConnectionPool, AppError, Household] =
      repo.update(id, patch).flatMap:
        case Some(h) => ZIO.succeed(h)
        case None    => ZIO.fail(AppError.NotFound("household", id.toString))

    /** Delete a household; fails with NotFound if absent, passes through ReferentialIntegrityError. */
    def deleteHousehold(id: UUID): ZIO[ZConnectionPool, AppError, Unit] =
      repo.delete(id).flatMap:
        case true  => ZIO.unit
        case false => ZIO.fail(AppError.NotFound("household", id.toString))

    /** Add a person to a household; the repository handles duplicate-membership conflicts. */
    def addMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, PersonHousehold] =
      repo.addMember(personId, householdId)

    /** Remove a person from a household; succeeds silently if the membership did not exist. */
    def removeMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, Unit] =
      repo.removeMember(personId, householdId).unit

    /** List all PersonHousehold membership records for a household. */
    def listMembers(householdId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]] =
      repo.listMembers(householdId)

    /** List all household memberships for a person. */
    def listPersonHouseholds(personId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]] =
      repo.listPersonHouseholds(personId)

  /** ZLayer providing the live HouseholdService. */
  val live: ZLayer[HouseholdRepository, Nothing, HouseholdService] =
    ZLayer.fromFunction(new Live(_))
