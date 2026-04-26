package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.ReferenceRepository
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*

class ReferenceRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  test("listDomains — returns at least 7 seeded domains") {
    withContainers { container =>
      migrate(container)
      val domains = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> ReferenceRepository.live).build
              repo     = repoEnv.get[ReferenceRepository]
              list    <- repo.listDomains.provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      domains.size should be >= 7
    }
  }

  test("listSourceTypes — returns at least 5 seeded source types") {
    withContainers { container =>
      migrate(container)
      val sourceTypes = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> ReferenceRepository.live).build
              repo     = repoEnv.get[ReferenceRepository]
              list    <- repo.listSourceTypes.provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      sourceTypes.size should be >= 5
    }
  }

  test("createDomain — inserts a new domain and returns it") {
    withContainers { container =>
      migrate(container)
      val domain = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> ReferenceRepository.live).build
              repo     = repoEnv.get[ReferenceRepository]
              d       <- repo.createDomain("test_domain_ref", "A test domain for reference tests").provideEnvironment(ZEnvironment(pool))
            yield d
          }
        ).getOrThrowFiberFailure()
      }
      domain.name        shouldBe "test_domain_ref"
      domain.description shouldBe "A test domain for reference tests"
    }
  }

  test("createSourceType — inserts a new source type and returns it") {
    withContainers { container =>
      migrate(container)
      val sourceType = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> ReferenceRepository.live).build
              repo     = repoEnv.get[ReferenceRepository]
              st      <- repo.createSourceType("test_source_ref", "A test source type").provideEnvironment(ZEnvironment(pool))
            yield st
          }
        ).getOrThrowFiberFailure()
      }
      sourceType.name        shouldBe "test_source_ref"
      sourceType.description shouldBe "A test source type"
    }
  }

  test("listKinshipAliases(None) — returns a list without error") {
    withContainers { container =>
      migrate(container)
      val aliases = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> ReferenceRepository.live).build
              repo     = repoEnv.get[ReferenceRepository]
              list    <- repo.listKinshipAliases(None).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      aliases should not be null
    }
  }

  test("listKinshipAliases(Some(\"hindi\")) — filtered list without error") {
    withContainers { container =>
      migrate(container)
      val aliases = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> ReferenceRepository.live).build
              repo     = repoEnv.get[ReferenceRepository]
              list    <- repo.listKinshipAliases(Some("hindi")).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      aliases should not be null
    }
  }
