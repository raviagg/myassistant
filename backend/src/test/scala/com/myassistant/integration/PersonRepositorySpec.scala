package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{PersonRepository, RelationshipRepository}
import com.myassistant.domain.{CreatePerson, CreateRelationship, Gender, RelationType, UpdatePerson}
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*

/** Integration tests for PersonRepository against a real PostgreSQL instance managed by Testcontainers.
 *
 *  A fresh container is started once per suite, and Flyway migrations are applied before any test runs.
 *  Uses pgvector/pgvector:pg16 because V1 migration creates the `vector` extension.
 */
class PersonRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(
      dockerImageName = DockerImageName.parse("pgvector/pgvector:pg16"),
      databaseName    = "myassistant_test",
      username        = "test",
      password        = "test",
    )

  // ── Helpers ───────────────────────────────────────────────────────────────

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

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — inserts a person and returns it with a generated UUID") {
    withContainers { container =>
      migrate(container)
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              repo     = repoEnv.get[PersonRepository]
              person  <- repo.create(CreatePerson("Ravi Aggarwal", Gender.Male, None, Some("Ravi"), None))
                           .provideEnvironment(ZEnvironment(pool))
            yield person
          }
        ).getOrThrowFiberFailure()
      }
      result.fullName      shouldBe "Ravi Aggarwal"
      result.id            should not be null
      result.gender        shouldBe Gender.Male
      result.preferredName shouldBe Some("Ravi")
    }
  }

  test("findById — returns the inserted person") {
    withContainers { container =>
      migrate(container)
      val (created, found) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              repo     = repoEnv.get[PersonRepository]
              created <- repo.create(CreatePerson("Nirmala Devi", Gender.Female, None, None, None))
                           .provideEnvironment(ZEnvironment(pool))
              found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(pool))
            yield (created, found)
          }
        ).getOrThrowFiberFailure()
      }
      found.isDefined    shouldBe true
      found.get.id       shouldBe created.id
      found.get.fullName shouldBe "Nirmala Devi"
    }
  }

  test("listAll — returns all persons") {
    withContainers { container =>
      migrate(container)
      val persons = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              repo     = repoEnv.get[PersonRepository]
              _       <- repo.create(CreatePerson("Alice",   Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(CreatePerson("Bob",     Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(CreatePerson("Charlie", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(pool))
              list    <- repo.listAll(None).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      persons.size should be >= 3
      persons.map(_.fullName) should contain allOf ("Alice", "Bob", "Charlie")
    }
  }

  test("update — modifies fields and returns the updated record") {
    withContainers { container =>
      migrate(container)
      val updated = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              repo     = repoEnv.get[PersonRepository]
              created <- repo.create(CreatePerson("OldName", Gender.Male, None, None, None))
                           .provideEnvironment(ZEnvironment(pool))
              updated <- repo.update(
                           created.id,
                           UpdatePerson(fullName = Some("NewName"), gender = None, dateOfBirth = None,
                                        preferredName = Some("Nick"), userIdentifier = None)
                         ).provideEnvironment(ZEnvironment(pool))
            yield updated
          }
        ).getOrThrowFiberFailure()
      }
      updated.isDefined         shouldBe true
      updated.get.fullName      shouldBe "NewName"
      updated.get.preferredName shouldBe Some("Nick")
    }
  }

  test("delete — removes person from database") {
    withContainers { container =>
      migrate(container)
      val (deleted, found) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              repo     = repoEnv.get[PersonRepository]
              created <- repo.create(CreatePerson("TempPerson", Gender.Female, None, None, None))
                           .provideEnvironment(ZEnvironment(pool))
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

  test("delete — fails with ReferentialIntegrityError when relationships exist") {
    withContainers { container =>
      migrate(container)
      val deleteResult = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv    <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool        = poolEnv.get[ZConnectionPool]
              personEnv  <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              relEnv     <- (ZLayer.succeed(pool) >>> RelationshipRepository.live).build
              personRepo  = personEnv.get[PersonRepository]
              relRepo     = relEnv.get[RelationshipRepository]
              person1    <- personRepo.create(CreatePerson("PersonA", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(pool))
              person2    <- personRepo.create(CreatePerson("PersonB", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(pool))
              _          <- relRepo.create(CreateRelationship(person1.id, person2.id, RelationType.Wife)).provideEnvironment(ZEnvironment(pool))
              delResult  <- personRepo.delete(person1.id).provideEnvironment(ZEnvironment(pool)).exit
            yield delResult
          }
        ).getOrThrowFiberFailure()
      }
      deleteResult.isFailure shouldBe true
      val appError: Option[AppError] = deleteResult match
        case zio.Exit.Failure(cause) => cause.failureOption
        case _                       => None
      appError.isDefined shouldBe true
      appError.get       shouldBe a [AppError.ReferentialIntegrityError]
    }
  }
