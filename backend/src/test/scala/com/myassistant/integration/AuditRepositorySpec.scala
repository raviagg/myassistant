package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{AuditRepository, PersonRepository}
import com.myassistant.domain.{AuditLog, CreatePerson, Gender, InteractionStatus}
import com.myassistant.errors.AppError
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.time.Instant
import java.util.UUID

class AuditRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  test("create (job entry) — inserts and returns audit log entry") {
    withContainers { container =>
      migrate(container)
      val entry = AuditLog(
        id        = UUID.randomUUID(),
        personId  = None,
        jobType   = Some("plaid_poll"),
        message   = "poll result",
        response  = None,
        toolCalls = Json.arr(),
        status    = InteractionStatus.Success,
        error     = None,
        createdAt = Instant.now(),
      )
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> AuditRepository.live).build
              repo     = repoEnv.get[AuditRepository]
              r       <- repo.create(entry).provideEnvironment(ZEnvironment(pool))
            yield r
          }
        ).getOrThrowFiberFailure()
      }
      result.id      shouldBe entry.id
      result.jobType shouldBe Some("plaid_poll")
      result.message shouldBe "poll result"
      result.status  shouldBe InteractionStatus.Success
    }
  }

  test("create (person entry) — creates a person first then inserts audit log with personId") {
    withContainers { container =>
      migrate(container)
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv   <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool       = poolEnv.get[ZConnectionPool]
              personEnv <- (ZLayer.succeed(pool) >>> PersonRepository.live).build
              auditEnv  <- (ZLayer.succeed(pool) >>> AuditRepository.live).build
              personRepo = personEnv.get[PersonRepository]
              auditRepo  = auditEnv.get[AuditRepository]
              person    <- personRepo.create(CreatePerson("Audit Person", Gender.Male, None, None, None)).provideEnvironment(ZEnvironment(pool))
              entry      = AuditLog(
                             id        = UUID.randomUUID(),
                             personId  = Some(person.id),
                             jobType   = None,
                             message   = "user chat message",
                             response  = Some("agent response"),
                             toolCalls = Json.arr(),
                             status    = InteractionStatus.Success,
                             error     = None,
                             createdAt = Instant.now(),
                           )
              r         <- auditRepo.create(entry).provideEnvironment(ZEnvironment(pool))
            yield r
          }
        ).getOrThrowFiberFailure()
      }
      result.personId.isDefined shouldBe true
      result.jobType            shouldBe None
      result.message            shouldBe "user chat message"
      result.response           shouldBe Some("agent response")
    }
  }

  test("findById — returns by ID and None for missing") {
    withContainers { container =>
      migrate(container)
      val (found, missing) = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> AuditRepository.live).build
              repo     = repoEnv.get[AuditRepository]
              entry    = AuditLog(
                           id        = UUID.randomUUID(),
                           personId  = None,
                           jobType   = Some("gmail_poll"),
                           message   = "find by id test",
                           response  = None,
                           toolCalls = Json.arr(),
                           status    = InteractionStatus.Partial,
                           error     = Some("partial error"),
                           createdAt = Instant.now(),
                         )
              created <- repo.create(entry).provideEnvironment(ZEnvironment(pool))
              found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(pool))
              missing <- repo.findById(UUID.randomUUID()).provideEnvironment(ZEnvironment(pool))
            yield (found, missing)
          }
        ).getOrThrowFiberFailure()
      }
      found.isDefined     shouldBe true
      found.get.jobType   shouldBe Some("gmail_poll")
      found.get.status    shouldBe InteractionStatus.Partial
      missing             shouldBe None
    }
  }

  test("listByJobType — returns entries for a job type using limit/offset") {
    withContainers { container =>
      migrate(container)
      val entries = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(
          ZIO.scoped {
            for
              poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive).build
              pool     = poolEnv.get[ZConnectionPool]
              repoEnv <- (ZLayer.succeed(pool) >>> AuditRepository.live).build
              repo     = repoEnv.get[AuditRepository]
              e1       = AuditLog(UUID.randomUUID(), None, Some("plaid_poll"), "msg 1", None, Json.arr(), InteractionStatus.Success, None, Instant.now())
              e2       = AuditLog(UUID.randomUUID(), None, Some("plaid_poll"), "msg 2", None, Json.arr(), InteractionStatus.Failed,  Some("err"), Instant.now())
              e3       = AuditLog(UUID.randomUUID(), None, Some("other_poll"), "msg 3", None, Json.arr(), InteractionStatus.Success, None, Instant.now())
              _       <- repo.create(e1).provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(e2).provideEnvironment(ZEnvironment(pool))
              _       <- repo.create(e3).provideEnvironment(ZEnvironment(pool))
              list    <- repo.listByJobType("plaid_poll", 10, 0).provideEnvironment(ZEnvironment(pool))
            yield list
          }
        ).getOrThrowFiberFailure()
      }
      entries.size should be >= 2
      entries.forall(_.jobType.contains("plaid_poll")) shouldBe true
    }
  }
