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
 */
class PersonRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

  // pgvector/pgvector:pg16 includes both PostgreSQL 16 and the pgvector extension,
  // which is required by V1__create_extensions.sql and the VECTOR columns.
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

  private def poolLayer(container: PostgreSQLContainer): ZLayer[Any, Throwable, ZConnectionPool] =
    DatabaseModule.connectionPoolLive.provide(ZLayer.succeed(dbConfig(container)))

  private def migrate(container: PostgreSQLContainer): Unit =
    val cfg      = dbConfig(container)
    val cfgLayer = ZLayer.succeed(cfg)
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        MigrationRunner.migrate.provide(cfgLayer)
      ).getOrThrowFiberFailure()
    }

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — inserts a person and returns it with a generated UUID") {
    withContainers { container =>
      migrate(container)
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env    <- PersonRepository.live.build.scoped
            repo    = env.get[PersonRepository]
            person <- repo.create(CreatePerson("Ravi Aggarwal", Gender.Male, None, Some("Ravi"), None))
                        .provide(pool)
          yield person)
        }.getOrThrowFiberFailure()
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
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env     <- PersonRepository.live.build.scoped
            repo     = env.get[PersonRepository]
            created <- repo.create(CreatePerson("Nirmala Devi", Gender.Female, None, None, None)).provide(pool)
            found   <- repo.findById(created.id).provide(pool)
          yield (created, found))
        }.getOrThrowFiberFailure()
      }
      found            shouldBe defined
      found.get.id     shouldBe created.id
      found.get.fullName shouldBe "Nirmala Devi"
    }
  }

  test("listAll — returns all persons") {
    withContainers { container =>
      migrate(container)
      val persons = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env  <- PersonRepository.live.build.scoped
            repo  = env.get[PersonRepository]
            _    <- repo.create(CreatePerson("Alice",   Gender.Female, None, None, None)).provide(pool)
            _    <- repo.create(CreatePerson("Bob",     Gender.Male,   None, None, None)).provide(pool)
            _    <- repo.create(CreatePerson("Charlie", Gender.Male,   None, None, None)).provide(pool)
            list <- repo.listAll(None).provide(pool)
          yield list)
        }.getOrThrowFiberFailure()
      }
      persons.size should be >= 3
      persons.map(_.fullName) should contain allOf ("Alice", "Bob", "Charlie")
    }
  }

  test("update — modifies fields and returns the updated record") {
    withContainers { container =>
      migrate(container)
      val updated = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env     <- PersonRepository.live.build.scoped
            repo     = env.get[PersonRepository]
            created <- repo.create(CreatePerson("OldName", Gender.Male, None, None, None)).provide(pool)
            updated <- repo.update(
                         created.id,
                         UpdatePerson(fullName = Some("NewName"), gender = None, dateOfBirth = None,
                                      preferredName = Some("Nick"), userIdentifier = None)
                       ).provide(pool)
          yield updated)
        }.getOrThrowFiberFailure()
      }
      updated shouldBe defined
      updated.get.fullName                  shouldBe "NewName"
      updated.get.preferredName             shouldBe Some("Nick")
    }
  }

  test("delete — removes person from database") {
    withContainers { container =>
      migrate(container)
      val (deleted, found) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env     <- PersonRepository.live.build.scoped
            repo     = env.get[PersonRepository]
            created <- repo.create(CreatePerson("TempPerson", Gender.Female, None, None, None)).provide(pool)
            deleted <- repo.delete(created.id).provide(pool)
            found   <- repo.findById(created.id).provide(pool)
          yield (deleted, found))
        }.getOrThrowFiberFailure()
      }
      deleted shouldBe true
      found   shouldBe None
    }
  }

  test("delete — fails with ReferentialIntegrityError when relationships exist") {
    withContainers { container =>
      migrate(container)
      // Arrange: create two persons and a relationship between them
      val deleteResult = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            personEnv <- PersonRepository.live.build.scoped
            relEnv    <- RelationshipRepository.live.build.scoped
            personRepo = personEnv.get[PersonRepository]
            relRepo    = relEnv.get[RelationshipRepository]
            person1   <- personRepo.create(CreatePerson("PersonA", Gender.Male,   None, None, None)).provide(pool)
            person2   <- personRepo.create(CreatePerson("PersonB", Gender.Female, None, None, None)).provide(pool)
            _         <- relRepo.create(CreateRelationship(person1.id, person2.id, RelationType.Wife)).provide(pool)
            // Attempt to delete person1 — should fail due to relationship FK
            delResult <- personRepo.delete(person1.id).provide(pool).exit
          yield delResult)
        }.getOrThrowFiberFailure()
      }
      // Assert: the delete must have failed
      deleteResult.isFailure shouldBe true
      // Extract the AppError from the Exit failure
      val appError: Option[AppError] = deleteResult match
        case zio.Exit.Failure(cause) => cause.failureOption
        case _                       => None
      appError.isDefined    shouldBe true
      appError.get          shouldBe a [AppError.ReferentialIntegrityError]
    }
  }
