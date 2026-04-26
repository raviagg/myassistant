package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{HouseholdRepository, PersonRepository}
import com.myassistant.domain.{CreateHousehold, CreatePerson, Gender, UpdateHousehold}
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.util.UUID

class HouseholdRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(
      dockerImageName = DockerImageName.parse("pgvector/pgvector:pg16"),
      databaseName    = "myassistant_test",
      username        = "test",
      password        = "test",
    )

  private def dbConfig(container: PostgreSQLContainer): DatabaseConfig =
    DatabaseConfig(
      url               = container.jdbcUrl,
      user              = container.username,
      password          = container.password,
      poolSize          = 2,
      connectionTimeout = 5000,
      idleTimeout       = 30000,
      maxLifetime       = 60000,
    )

  private def migrate(container: PostgreSQLContainer): Unit =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        MigrationRunner.migrate.provide(ZLayer.succeed(dbConfig(container)))
      ).getOrThrowFiberFailure()
    }

  private def run[A](container: PostgreSQLContainer)(effect: ZIO[ZConnectionPool, AppError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        ZIO.scoped {
          for
            poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
            pool     = poolEnv.get[ZConnectionPool]
            result  <- effect.provideEnvironment(ZEnvironment(pool))
          yield result
        }
      ).getOrThrowFiberFailure()
    }

  test("create — inserts household and returns it with a generated UUID") {
    withContainers { container =>
      migrate(container)
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              repo     = repoEnv.get[HouseholdRepository]
              h       <- repo.create(CreateHousehold("Test Family")).provideEnvironment(ZEnvironment(pool))
            yield h
          }
        ).getOrThrowFiberFailure()
      }
      result.name   shouldBe "Test Family"
      result.id     should not be null
    }
  }

  test("findById — returns the inserted household") {
    withContainers { container =>
      migrate(container)
      val (created, found) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              repo     = repoEnv.get[HouseholdRepository]
              created <- repo.create(CreateHousehold("FindById Family")).provideEnvironment(ZEnvironment(pool))
              found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(pool))
            yield (created, found)
          }
        ).getOrThrowFiberFailure()
      }
      found.isDefined    shouldBe true
      found.get.id       shouldBe created.id
      found.get.name     shouldBe "FindById Family"
    }
  }

  test("listAll — returns all households") {
    withContainers { container =>
      migrate(container)
      val households = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              repo     = repoEnv.get[HouseholdRepository]
              _       <- repo.create(CreateHousehold("Alpha Family")).provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(CreateHousehold("Beta Family")).provideEnvironment(ZEnvironment(pool))
              list    <- repo.listAll.provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      households.size should be >= 2
      households.map(_.name) should contain allOf ("Alpha Family", "Beta Family")
    }
  }

  test("update — modifies name and returns updated record") {
    withContainers { container =>
      migrate(container)
      val updated = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              repo     = repoEnv.get[HouseholdRepository]
              created <- repo.create(CreateHousehold("Old Name")).provideEnvironment(ZEnvironment(pool))
              updated <- repo.update(created.id, UpdateHousehold(name = Some("New Name"))).provideEnvironment(ZEnvironment(pool))
            yield updated
          }
        ).getOrThrowFiberFailure()
      }
      updated.isDefined  shouldBe true
      updated.get.name   shouldBe "New Name"
    }
  }

  test("delete — removes household from database") {
    withContainers { container =>
      migrate(container)
      val (deleted, found) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              repo     = repoEnv.get[HouseholdRepository]
              created <- repo.create(CreateHousehold("Temp Household")).provideEnvironment(ZEnvironment(pool))
              deleted <- repo.delete(created.id).provideEnvironment(ZEnvironment(pool))
              found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(pool))
            yield (deleted, found)
          }
        ).getOrThrowFiberFailure()
      }
      deleted shouldBe true
      found   shouldBe None
    }
  }

  test("addMember — inserts person_household row") {
    withContainers { container =>
      migrate(container)
      val membership = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv    <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool        = poolEnv.get[ZConnectionPool]
              personEnv  <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              householdEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              personRepo  = personEnv.get[PersonRepository]
              hRepo       = householdEnv.get[HouseholdRepository]
              person     <- personRepo.create(CreatePerson("AddMember Person", Gender.Male, None, None, None)).provideEnvironment(ZEnvironment(pool))
              household  <- hRepo.create(CreateHousehold("AddMember Household")).provideEnvironment(ZEnvironment(pool))
              membership <- hRepo.addMember(person.id, household.id).provideEnvironment(ZEnvironment(pool))
            yield membership
          }
        ).getOrThrowFiberFailure()
      }
      membership.personId    should not be null
      membership.householdId should not be null
    }
  }

  test("listMembers — returns members of a household") {
    withContainers { container =>
      migrate(container)
      val members = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv      <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool          = poolEnv.get[ZConnectionPool]
              personEnv    <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              householdEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              personRepo    = personEnv.get[PersonRepository]
              hRepo         = householdEnv.get[HouseholdRepository]
              p1           <- personRepo.create(CreatePerson("Member One", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(pool))
              p2           <- personRepo.create(CreatePerson("Member Two", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(pool))
              household    <- hRepo.create(CreateHousehold("ListMembers Household")).provideEnvironment(ZEnvironment(pool))
              _            <- hRepo.addMember(p1.id, household.id).provideEnvironment(ZEnvironment(pool))
              _            <- hRepo.addMember(p2.id, household.id).provideEnvironment(ZEnvironment(pool))
              members      <- hRepo.listMembers(household.id).provideEnvironment(ZEnvironment(pool))
            yield members
          }
        ).getOrThrowFiberFailure()
      }
      members.size should be >= 2
      members.forall(_.householdId != null) shouldBe true
    }
  }

  test("removeMember — removes the membership") {
    withContainers { container =>
      migrate(container)
      val (removed, membersAfter) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv      <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool          = poolEnv.get[ZConnectionPool]
              personEnv    <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              householdEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              personRepo    = personEnv.get[PersonRepository]
              hRepo         = householdEnv.get[HouseholdRepository]
              person       <- personRepo.create(CreatePerson("Remove Member Person", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(pool))
              household    <- hRepo.create(CreateHousehold("RemoveMember Household")).provideEnvironment(ZEnvironment(pool))
              _            <- hRepo.addMember(person.id, household.id).provideEnvironment(ZEnvironment(pool))
              removed      <- hRepo.removeMember(person.id, household.id).provideEnvironment(ZEnvironment(pool))
              membersAfter <- hRepo.listMembers(household.id).provideEnvironment(ZEnvironment(pool))
            yield (removed, membersAfter)
          }
        ).getOrThrowFiberFailure()
      }
      removed      shouldBe true
      membersAfter shouldBe List.empty
    }
  }

  test("listPersonHouseholds — returns all households for a person") {
    withContainers { container =>
      migrate(container)
      val personHouseholds = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv      <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool          = poolEnv.get[ZConnectionPool]
              personEnv    <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              householdEnv <- (ZLayer.succeed(pool) >>> HouseholdRepository.live).build
              personRepo    = personEnv.get[PersonRepository]
              hRepo         = householdEnv.get[HouseholdRepository]
              person       <- personRepo.create(CreatePerson("Multi Household Person", Gender.Male, None, None, None)).provideEnvironment(ZEnvironment(pool))
              h1           <- hRepo.create(CreateHousehold("PersonHouseholds H1")).provideEnvironment(ZEnvironment(pool))
              h2           <- hRepo.create(CreateHousehold("PersonHouseholds H2")).provideEnvironment(ZEnvironment(pool))
              _            <- hRepo.addMember(person.id, h1.id).provideEnvironment(ZEnvironment(pool))
              _            <- hRepo.addMember(person.id, h2.id).provideEnvironment(ZEnvironment(pool))
              pHouseholds  <- hRepo.listPersonHouseholds(person.id).provideEnvironment(ZEnvironment(pool))
            yield pHouseholds
          }
        ).getOrThrowFiberFailure()
      }
      personHouseholds.size should be >= 2
      personHouseholds.forall(_.personId != null) shouldBe true
    }
  }
