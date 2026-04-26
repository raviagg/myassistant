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
 *  Uses pgvector/pgvector:pg16 because V1 migration creates the `vector` extension.
 */
class FactRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  /** Run a ZIO effect that only needs ZConnectionPool against the Testcontainers pool. */
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

  /** Seed a person and a document and return (docId, schemaId) from the first seeded schema row. */
  private def seedPrerequisites(container: PostgreSQLContainer): (UUID, UUID) =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        ZIO.scoped {
          for
            poolEnv   <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
            pool       = poolEnv.get[ZConnectionPool]
            personEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
            docEnv    <- (ZLayer.succeed(pool) >>> DocumentRepository.live).build
            person    <- personEnv.get[PersonRepository]
                           .create(CreatePerson("Fact Test Person", Gender.Male, None, None, None))
                           .provideEnvironment(ZEnvironment(pool))
            // Look up the first entity_type_schema row seeded by the V5 migration
            schemaIdStr <- transaction(
                             sql"SELECT id::text FROM entity_type_schema LIMIT 1"
                               .query[String].selectOne
                           ).mapError(AppError.DatabaseError(_))
                            .flatMap(ZIO.fromOption(_).mapError(_ =>
                              AppError.InternalError(new RuntimeException("No schema seed found"))))
                            .provideEnvironment(ZEnvironment(pool))
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
                             .provideEnvironment(ZEnvironment(pool))
          yield (doc.id, schemaId)
        }
      ).getOrThrowFiberFailure()
    }

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — inserts a fact row and returns it with a generated UUID") {
    withContainers { container =>
      migrate(container)
      val (docId, schemaId) = seedPrerequisites(container)
      val fact = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> FactRepository.live).build
              repo     = repoEnv.get[FactRepository]
              result  <- repo.create(CreateFact(
                           documentId       = docId,
                           schemaId         = schemaId,
                           entityInstanceId = None,
                           operationType    = OperationType.Create,
                           fields           = Json.obj(
                             "title"  -> Json.fromString("Test task"),
                             "status" -> Json.fromString("open"),
                           ),
                         )).provideEnvironment(ZEnvironment(pool))
            yield result
          }
        ).getOrThrowFiberFailure()
      }
      fact.id            should not be null
      fact.documentId    shouldBe docId
      fact.schemaId      shouldBe schemaId
      fact.operationType shouldBe OperationType.Create
    }
  }

  test("findByEntityInstance — returns all facts for an entity instance in creation order") {
    withContainers { container =>
      migrate(container)
      val (docId, schemaId) = seedPrerequisites(container)
      val entityId          = UUID.randomUUID()
      val facts = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> FactRepository.live).build
              repo     = repoEnv.get[FactRepository]
              _       <- repo.create(CreateFact(docId, schemaId, Some(entityId), OperationType.Create,
                           Json.obj("title"  -> Json.fromString("Renew passport"),
                                    "status" -> Json.fromString("open"))))
                           .provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(CreateFact(docId, schemaId, Some(entityId), OperationType.Update,
                           Json.obj("status" -> Json.fromString("in_progress"))))
                           .provideEnvironment(ZEnvironment(pool))
              list    <- repo.findByEntityInstance(entityId).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      facts.size                             shouldBe 2
      facts.map(_.entityInstanceId).distinct shouldBe List(entityId)
      facts.map(_.operationType).toSet should contain (OperationType.Create)
      facts.map(_.operationType).toSet should contain (OperationType.Update)
    }
  }

  test("findByDocument — returns all facts for a document") {
    withContainers { container =>
      migrate(container)
      val (docId, schemaId) = seedPrerequisites(container)
      val facts = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> FactRepository.live).build
              repo     = repoEnv.get[FactRepository]
              _       <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                           Json.obj("employer"     -> Json.fromString("Acme"),
                                    "pay_period"   -> Json.fromString("2024-01-31"),
                                    "gross_income" -> Json.fromInt(10000))))
                           .provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                           Json.obj("employer"     -> Json.fromString("Beta Corp"),
                                    "pay_period"   -> Json.fromString("2024-02-29"),
                                    "gross_income" -> Json.fromInt(11000))))
                           .provideEnvironment(ZEnvironment(pool))
              list    <- repo.findByDocument(docId).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      facts.size                       should be >= 2
      facts.map(_.documentId).distinct shouldBe List(docId)
    }
  }
