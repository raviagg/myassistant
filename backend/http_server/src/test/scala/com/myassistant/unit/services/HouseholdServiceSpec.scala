package com.myassistant.unit.services

import com.myassistant.db.repositories.HouseholdRepository
import com.myassistant.domain.{CreateHousehold, Household, PersonHousehold, UpdateHousehold}
import com.myassistant.errors.AppError
import com.myassistant.services.HouseholdService
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object HouseholdServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock HouseholdRepository ───────────────────────

  final class MockHouseholdRepository(
      store:      Ref[Map[UUID, Household]],
      membership: Ref[Map[(UUID, UUID), PersonHousehold]],
  ) extends HouseholdRepository:

    private def now = Instant.now()

    def create(req: CreateHousehold): ZIO[ZConnectionPool, AppError, Household] =
      val h = Household(UUID.randomUUID(), req.name, now, now)
      store.update(_ + (h.id -> h)).as(h)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Household]] =
      store.get.map(_.get(id))

    def searchByName(name: String): ZIO[ZConnectionPool, AppError, List[Household]] =
      store.get.map(_.values.filter(_.name.toLowerCase.contains(name.toLowerCase)).toList.sortBy(_.name))

    def update(id: UUID, patch: UpdateHousehold): ZIO[ZConnectionPool, AppError, Option[Household]] =
      store.get.flatMap: m =>
        m.get(id) match
          case None => ZIO.succeed(None)
          case Some(existing) =>
            val updated = existing.copy(name = patch.name.getOrElse(existing.name), updatedAt = now)
            store.update(_ + (id -> updated)).as(Some(updated))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      store.get.flatMap: m =>
        if m.contains(id) then store.update(_ - id).as(true)
        else ZIO.succeed(false)

    def addMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, PersonHousehold] =
      val ph = PersonHousehold(personId, householdId, now)
      membership.update(_ + ((personId, householdId) -> ph)).as(ph)

    def removeMember(personId: UUID, householdId: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      membership.update(_ - ((personId, householdId))).as(true)

    def listMembers(householdId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]] =
      membership.get.map(_.values.filter(_.householdId == householdId).toList)

    def listPersonHouseholds(personId: UUID): ZIO[ZConnectionPool, AppError, List[PersonHousehold]] =
      membership.get.map(_.values.filter(_.personId == personId).toList)

  // ── Layer factory ─────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, HouseholdRepository] =
    ZLayer.fromZIO:
      for
        store      <- Ref.make(Map.empty[UUID, Household])
        membership <- Ref.make(Map.empty[(UUID, UUID), PersonHousehold])
      yield new MockHouseholdRepository(store, membership)

  private def withFreshService[E](spec: Spec[HouseholdService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, HouseholdService.live, ZConnectionPool.h2test.orDie)

  // ── Tests ─────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("HouseholdServiceSpec")(

      withFreshService(
        suite("createHousehold")(

          test("stores a household and returns it with a generated id") {
            for
              svc    <- ZIO.service[HouseholdService]
              result <- svc.createHousehold(CreateHousehold("Sharma Family"))
            yield assertTrue(result.name == "Sharma Family") &&
                  assertTrue(result.id != null)
          },

        )
      ),

      withFreshService(
        suite("getHousehold")(

          test("returns NotFound when household does not exist") {
            for
              svc    <- ZIO.service[HouseholdService]
              result <- svc.getHousehold(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the household when it exists") {
            for
              svc     <- ZIO.service[HouseholdService]
              created <- svc.createHousehold(CreateHousehold("Test Family"))
              found   <- svc.getHousehold(created.id)
            yield assertTrue(found.id == created.id) &&
                  assertTrue(found.name == "Test Family")
          },

        )
      ),

      withFreshService(
        suite("searchHouseholds")(

          test("returns empty list when no households match the name") {
            for
              svc    <- ZIO.service[HouseholdService]
              result <- svc.searchHouseholds("nonexistent")
            yield assertTrue(result.isEmpty)
          },

          test("returns households matching the name substring") {
            for
              svc  <- ZIO.service[HouseholdService]
              _    <- svc.createHousehold(CreateHousehold("Alpha Family"))
              _    <- svc.createHousehold(CreateHousehold("Beta Family"))
              _    <- svc.createHousehold(CreateHousehold("Gamma Crew"))
              list <- svc.searchHouseholds("Family")
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.map(_.name).contains("Alpha Family")) &&
                  assertTrue(list.map(_.name).contains("Beta Family"))
          },

        )
      ),

      withFreshService(
        suite("updateHousehold")(

          test("applies name patch and returns updated record") {
            for
              svc     <- ZIO.service[HouseholdService]
              created <- svc.createHousehold(CreateHousehold("Old Name"))
              updated <- svc.updateHousehold(created.id, UpdateHousehold(Some("New Name")))
            yield assertTrue(updated.id == created.id) &&
                  assertTrue(updated.name == "New Name")
          },

          test("returns NotFound when household does not exist") {
            for
              svc    <- ZIO.service[HouseholdService]
              result <- svc.updateHousehold(UUID.randomUUID(), UpdateHousehold(Some("X"))).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

      withFreshService(
        suite("deleteHousehold")(

          test("removes the household successfully") {
            for
              svc     <- ZIO.service[HouseholdService]
              created <- svc.createHousehold(CreateHousehold("TempHousehold"))
              _       <- svc.deleteHousehold(created.id)
              found   <- svc.getHousehold(created.id).exit
            yield assert(found)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns NotFound when household does not exist") {
            for
              svc    <- ZIO.service[HouseholdService]
              result <- svc.deleteHousehold(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

      withFreshService(
        suite("membership")(

          test("addMember and listMembers round-trip") {
            val personId    = UUID.randomUUID()
            val householdId = UUID.randomUUID()
            for
              svc     <- ZIO.service[HouseholdService]
              _       <- svc.addMember(personId, householdId)
              members <- svc.listMembers(householdId)
            yield assertTrue(members.exists(_.personId == personId))
          },

          test("removeMember silently succeeds when membership existed") {
            val personId    = UUID.randomUUID()
            val householdId = UUID.randomUUID()
            for
              svc     <- ZIO.service[HouseholdService]
              _       <- svc.addMember(personId, householdId)
              _       <- svc.removeMember(personId, householdId)
              members <- svc.listMembers(householdId)
            yield assertTrue(members.isEmpty)
          },

          test("listPersonHouseholds returns all households for a person") {
            val personId = UUID.randomUUID()
            val hid1     = UUID.randomUUID()
            val hid2     = UUID.randomUUID()
            for
              svc  <- ZIO.service[HouseholdService]
              _    <- svc.addMember(personId, hid1)
              _    <- svc.addMember(personId, hid2)
              list <- svc.listPersonHouseholds(personId)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.map(_.householdId).contains(hid1)) &&
                  assertTrue(list.map(_.householdId).contains(hid2))
          },

        )
      ),

    )
