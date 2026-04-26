package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.SchemaRepository
import com.myassistant.domain.ProposeEntityTypeSchema
import com.myassistant.errors.AppError
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.util.UUID

class SchemaRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  private val testProposal = ProposeEntityTypeSchema(
    domain            = "test_domain",
    entityType        = "test_entity",
    description       = "Test",
    fieldDefinitions  = Json.arr(),
    extractionPrompt  = "Extract test facts",
    changeDescription = None,
  )

  test("create — proposes new schema with version 1") {
    withContainers { container =>
      migrate(container)
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> SchemaRepository.live).build
              repo     = repoEnv.get[SchemaRepository]
              schema  <- repo.create(testProposal).provideEnvironment(ZEnvironment(pool))
            yield schema
          }
        ).getOrThrowFiberFailure()
      }
      result.domain        shouldBe "test_domain"
      result.entityType    shouldBe "test_entity"
      result.schemaVersion shouldBe 1
      result.isActive      shouldBe true
      result.id            should not be null
    }
  }

  test("findById — returns the created schema") {
    withContainers { container =>
      migrate(container)
      val (created, found) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> SchemaRepository.live).build
              repo     = repoEnv.get[SchemaRepository]
              created <- repo.create(testProposal).provideEnvironment(ZEnvironment(pool))
              found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(pool))
            yield (created, found)
          }
        ).getOrThrowFiberFailure()
      }
      found.isDefined         shouldBe true
      found.get.id            shouldBe created.id
      found.get.entityType    shouldBe "test_entity"
    }
  }

  test("findCurrent — returns active schema for seeded health/insurance_card pair") {
    withContainers { container =>
      migrate(container)
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> SchemaRepository.live).build
              repo     = repoEnv.get[SchemaRepository]
              schema  <- repo.findCurrent("health", "insurance_card").provideEnvironment(ZEnvironment(pool))
            yield schema
          }
        ).getOrThrowFiberFailure()
      }
      result.isDefined            shouldBe true
      result.get.domain           shouldBe "health"
      result.get.entityType       shouldBe "insurance_card"
      result.get.isActive         shouldBe true
    }
  }

  test("listCurrent(None) — returns at least 4 seeded schemas") {
    withContainers { container =>
      migrate(container)
      val schemas = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> SchemaRepository.live).build
              repo     = repoEnv.get[SchemaRepository]
              list    <- repo.listCurrent(None).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      schemas.size should be >= 4
    }
  }

  test("listCurrent(Some(\"health\")) — returns schemas for health domain only") {
    withContainers { container =>
      migrate(container)
      val schemas = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> SchemaRepository.live).build
              repo     = repoEnv.get[SchemaRepository]
              list    <- repo.listCurrent(Some("health")).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      schemas should not be empty
      schemas.forall(_.domain == "health") shouldBe true
    }
  }

  test("deactivate — sets isActive=false and subsequent findCurrent returns None") {
    withContainers { container =>
      migrate(container)
      val (deactivated, foundAfter) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv     <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool         = poolEnv.get[ZConnectionPool]
              repoEnv     <- (ZLayer.succeed(pool) >>> SchemaRepository.live).build
              repo         = repoEnv.get[SchemaRepository]
              created     <- repo.create(testProposal).provideEnvironment(ZEnvironment(pool))
              deactivated <- repo.deactivate(created.id).provideEnvironment(ZEnvironment(pool))
              foundAfter  <- repo.findCurrent("test_domain", "test_entity").provideEnvironment(ZEnvironment(pool))
            yield (deactivated, foundAfter)
          }
        ).getOrThrowFiberFailure()
      }
      deactivated  shouldBe true
      foundAfter   shouldBe None
    }
  }
