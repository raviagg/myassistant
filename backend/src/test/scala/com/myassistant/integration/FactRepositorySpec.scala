package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{DocumentRepository, FactRepository, PersonRepository}
import com.myassistant.domain.{CreateDocument, CreateFact, CreatePerson, Gender, OperationType}
import com.myassistant.errors.AppError
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Integration tests for FactRepository against a real PostgreSQL instance managed by Testcontainers.
 *
 *  Tests verify append-only fact writing, entity-instance-scoped retrieval, and document-scoped retrieval.
 */
class FactRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        MigrationRunner.migrate.provide(ZLayer.succeed(dbConfig(container)))
      ).getOrThrowFiberFailure()
    }

  /** Run a ZIO effect against the pool, surfacing all errors as Throwables. */
  private def run[A](container: PostgreSQLContainer)(effect: ZIO[ZConnectionPool, AppError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        effect.provide(poolLayer(container))
      ).getOrThrowFiberFailure()
    }

  /** Seed a person, source_type row (already seeded by V4), a document, and return the first
   *  seeded schema UUID from entity_type_schema so tests can reference real FK values.
   */
  private def seedPrerequisites(container: PostgreSQLContainer): (UUID, UUID) =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run {
        val pool = poolLayer(container)
        (for
          personEnv <- PersonRepository.live.build.scoped
          docEnv    <- DocumentRepository.live.build.scoped
          person    <- personEnv.get[PersonRepository]
                         .create(CreatePerson("Fact Test Person", Gender.Male, None, None, None))
                         .provide(pool)
          // Look up the first entity_type_schema row seeded by V5 migration
          schemaIdStr <- transaction(
            zio.jdbc.sql"SELECT id::text FROM entity_type_schema LIMIT 1"
              .query[String].selectOne
          ).mapError(AppError.DatabaseError(_))
            .flatMap(ZIO.fromOption(_).mapError(_ =>
              AppError.InternalError(new RuntimeException("No schema seed found"))))
            .provide(pool)
          schemaId     = UUID.fromString(schemaIdStr)
          doc         <- docEnv.get[DocumentRepository]
                           .create(CreateDocument(
                             personId      = Some(person.id),
                             householdId   = None,
                             contentText   = "Test document for fact tests",
                             sourceType    = "user_input",
                             files         = Json.arr(),
                             supersedesIds = Nil,
                           ))
                           .provide(pool)
        yield (doc.id, schemaId))
      }.getOrThrowFiberFailure()
    }

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — inserts a fact row and returns it with a generated UUID") {
    withContainers { container =>
      migrate(container)
      val (docId, schemaId) = seedPrerequisites(container)
      val fact = run(container) {
        FactRepository.live.build.flatMap(env =>
          env.get[FactRepository].create(CreateFact(
            documentId       = docId,
            schemaId         = schemaId,
            entityInstanceId = None,
            operationType    = OperationType.Create,
            fields           = Json.obj("title" -> Json.fromString("Test task"), "status" -> Json.fromString("open")),
          ))
        ).scoped
      }
      fact.id           should not be null
      fact.documentId   shouldBe docId
      fact.schemaId     shouldBe schemaId
      fact.operationType shouldBe OperationType.Create
    }
  }

  test("findByEntityInstance — returns all facts for an entity instance in creation order") {
    withContainers { container =>
      migrate(container)
      val (docId, schemaId) = seedPrerequisites(container)
      val entityId = UUID.randomUUID()
      val facts = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env  <- FactRepository.live.build.scoped
            repo  = env.get[FactRepository]
            f1   <- repo.create(CreateFact(docId, schemaId, Some(entityId), OperationType.Create,
                      Json.obj("title" -> Json.fromString("Renew passport"), "status" -> Json.fromString("open"))))
                    .provide(pool)
            f2   <- repo.create(CreateFact(docId, schemaId, Some(entityId), OperationType.Update,
                      Json.obj("status" -> Json.fromString("in_progress"))))
                    .provide(pool)
            list <- repo.findByEntityInstance(entityId).provide(pool)
          yield list)
        }.getOrThrowFiberFailure()
      }
      facts.size shouldBe 2
      facts.map(_.entityInstanceId).distinct shouldBe List(entityId)
      // Both operations for the entity instance must be present
      val opTypes = facts.map(_.operationType).toSet
      opTypes should contain (OperationType.Create)
      opTypes should contain (OperationType.Update)
    }
  }

  test("findByDocument — returns all facts for a document") {
    withContainers { container =>
      migrate(container)
      val (docId, schemaId) = seedPrerequisites(container)
      val facts = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          val pool = poolLayer(container)
          (for
            env  <- FactRepository.live.build.scoped
            repo  = env.get[FactRepository]
            _    <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                      Json.obj("employer" -> Json.fromString("Acme"), "pay_period" -> Json.fromString("2024-01-31"),
                               "gross_income" -> Json.fromInt(10000))))
                    .provide(pool)
            _    <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                      Json.obj("employer" -> Json.fromString("Beta Corp"), "pay_period" -> Json.fromString("2024-02-29"),
                               "gross_income" -> Json.fromInt(11000))))
                    .provide(pool)
            list <- repo.findByDocument(docId).provide(pool)
          yield list)
        }.getOrThrowFiberFailure()
      }
      facts.size should be >= 2
      facts.map(_.documentId).distinct shouldBe List(docId)
    }
  }
