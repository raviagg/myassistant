package com.myassistant.unit.services

import com.myassistant.db.repositories.PersonRepository
import com.myassistant.domain.{CreatePerson, Gender, Person, UpdatePerson}
import com.myassistant.errors.AppError
import com.myassistant.services.PersonService
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

/** Unit tests for PersonService business logic using an in-memory mock PersonRepository. */
object PersonServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock PersonRepository ──────────────────────────────────────

  /** Thread-safe in-memory store; uses a ZIO Ref so state is safe in concurrent tests. */
  final class MockPersonRepository(store: Ref[Map[UUID, Person]]) extends PersonRepository:

    def create(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person] =
      if req.fullName.isBlank then
        ZIO.fail(AppError.ValidationError("fullName must not be blank"))
      else
        val now = Instant.now()
        val p   = Person(
          id             = UUID.randomUUID(),
          fullName       = req.fullName,
          gender         = req.gender,
          dateOfBirth    = req.dateOfBirth,
          preferredName  = req.preferredName,
          userIdentifier = req.userIdentifier,
          createdAt      = now,
          updatedAt      = now,
        )
        store.update(_ + (p.id -> p)).as(p)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Person]] =
      store.get.map(_.get(id))

    def findByUserIdentifier(identifier: String): ZIO[ZConnectionPool, AppError, Option[Person]] =
      store.get.map(_.values.find(_.userIdentifier.contains(identifier)))

    def listAll(householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[Person]] =
      // Mock ignores householdId filtering — sufficient for unit tests
      store.get.map(_.values.toList.sortBy(_.fullName))

    def update(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Option[Person]] =
      store.get.flatMap: m =>
        m.get(id) match
          case None => ZIO.succeed(None)
          case Some(existing) =>
            val updated = existing.copy(
              fullName       = patch.fullName.getOrElse(existing.fullName),
              gender         = patch.gender.getOrElse(existing.gender),
              dateOfBirth    = patch.dateOfBirth.orElse(existing.dateOfBirth),
              preferredName  = patch.preferredName.orElse(existing.preferredName),
              userIdentifier = patch.userIdentifier.orElse(existing.userIdentifier),
              updatedAt      = Instant.now(),
            )
            store.update(_ + (id -> updated)).as(Some(updated))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      store.get.flatMap: m =>
        if m.contains(id) then store.update(_ - id).as(true)
        else ZIO.succeed(false)

  // ── Layer factory ─────────────────────────────────────────────────────────
  // `ZLayer.fromZIO` is re-evaluated each time `.provide` is called with it,
  // giving each test suite a fresh in-memory store.

  val mockRepoLayer: ZLayer[Any, Nothing, PersonRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, Person]).map(new MockPersonRepository(_)))

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def makeReq(name: String): CreatePerson =
    CreatePerson(fullName = name, gender = Gender.Male, dateOfBirth = None, preferredName = None, userIdentifier = None)

  // ── Tests ─────────────────────────────────────────────────────────────────

  /** Provide a fresh in-memory PersonService to each sub-suite independently. */
  private def withFreshService[E](spec: Spec[PersonService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, PersonService.live, ZConnectionPool.h2test.orDie)

  def spec: Spec[Any, Any] =
    suite("PersonServiceSpec")(

      withFreshService(
        suite("createPerson")(

          test("stores a new person and returns it with a generated id") {
            for
              svc    <- ZIO.service[PersonService]
              result <- svc.createPerson(makeReq("Raj Sharma"))
            yield assertTrue(result.fullName == "Raj Sharma") &&
                  assertTrue(result.id != null) &&
                  assertTrue(result.gender == Gender.Male)
          },

          test("fails with ValidationError when fullName is blank") {
            for
              svc    <- ZIO.service[PersonService]
              result <- svc.createPerson(makeReq("")).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

        )
      ),

      withFreshService(
        suite("getPerson")(

          test("returns NotFound when person does not exist") {
            for
              svc    <- ZIO.service[PersonService]
              result <- svc.getPerson(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the person when it exists") {
            for
              svc     <- ZIO.service[PersonService]
              created <- svc.createPerson(makeReq("Priya Sharma"))
              found   <- svc.getPerson(created.id)
            yield assertTrue(found.id == created.id) &&
                  assertTrue(found.fullName == "Priya Sharma")
          },

        )
      ),

      withFreshService(
        suite("listPersons")(

          test("returns empty list when no persons exist") {
            for
              svc    <- ZIO.service[PersonService]
              result <- svc.listPersons(None)
            yield assertTrue(result.isEmpty)
          },

          test("returns all created persons") {
            for
              svc  <- ZIO.service[PersonService]
              _    <- svc.createPerson(makeReq("Alice"))
              _    <- svc.createPerson(makeReq("Bob"))
              list <- svc.listPersons(None)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.map(_.fullName).contains("Alice")) &&
                  assertTrue(list.map(_.fullName).contains("Bob"))
          },

        )
      ),

      withFreshService(
        suite("updatePerson")(

          test("returns updated person with new fields") {
            val patch = UpdatePerson(
              fullName       = Some("Raj S."),
              gender         = None,
              dateOfBirth    = None,
              preferredName  = Some("Raj"),
              userIdentifier = None,
            )
            for
              svc     <- ZIO.service[PersonService]
              created <- svc.createPerson(makeReq("Raj Sharma"))
              updated <- svc.updatePerson(created.id, patch)
            yield assertTrue(updated.id == created.id) &&
                  assertTrue(updated.fullName == "Raj S.") &&
                  assertTrue(updated.preferredName.contains("Raj"))
          },

          test("returns NotFound when person does not exist") {
            val patch = UpdatePerson(Some("X"), None, None, None, None)
            for
              svc    <- ZIO.service[PersonService]
              result <- svc.updatePerson(UUID.randomUUID(), patch).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

      withFreshService(
        suite("deletePerson")(

          test("removes the person successfully") {
            for
              svc     <- ZIO.service[PersonService]
              created <- svc.createPerson(makeReq("TempUser"))
              _       <- svc.deletePerson(created.id)
              found   <- svc.getPerson(created.id).exit
            yield assert(found)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns NotFound when person does not exist") {
            for
              svc    <- ZIO.service[PersonService]
              result <- svc.deletePerson(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

    )
