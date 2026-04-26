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
import org.scalatest.Outcome
import scala.compiletime.uninitialized
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.util.UUID

class SchemaRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(
      dockerImageName = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"),
      databaseName    = "myassistant_test",
      username        = "test",
      password        = "test",
    )

  private var sharedPool: ZConnectionPool = uninitialized
  private var poolScope: Scope.Closeable  = uninitialized

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

  override def withFixture(test: NoArgTest): Outcome =
    println(s"[${getClass.getSimpleName}] >>> ${test.name}")
    val outcome = super.withFixture(test)
    println(s"[${getClass.getSimpleName}] <<< ${test.name} — ${outcome.getClass.getSimpleName}")
    outcome

  override def afterContainersStart(container: PostgreSQLContainer): Unit =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        for
          _ <- MigrationRunner.migrate
                 .provide(ZLayer.succeed(dbConfig(container)))
                 .timeoutFail(new RuntimeException("Migration timed out after 30s"))(30.seconds)
          scope   <- Scope.make
          poolEnv <- (ZLayer.succeed(dbConfig(container)) >>> DatabaseModule.connectionPoolLive)
                       .build
                       .provideEnvironment(ZEnvironment(scope))
          _        = sharedPool = poolEnv.get[ZConnectionPool]
          _        = poolScope  = scope
        yield ()
      ).getOrThrowFiberFailure()
    }

  override def beforeContainersStop(container: PostgreSQLContainer): Unit =
    if poolScope != null then
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(poolScope.close(Exit.succeed(()))).getOrThrowFiberFailure()
      }

  private def run[A](effect: ZIO[Scope, AppError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        ZIO.scoped(effect)
          .timeoutFail(new RuntimeException("Test timed out after 30s"))(30.seconds)
      ).getOrThrowFiberFailure()
    }

  private val testProposal = ProposeEntityTypeSchema(
    domain            = "health",
    entityType        = "test_entity",
    description       = "Test schema",
    fieldDefinitions  = Json.arr(
      Json.obj(
        "name"        -> Json.fromString("title"),
        "type"        -> Json.fromString("text"),
        "mandatory"   -> Json.fromBoolean(true),
        "description" -> Json.fromString("Test field"),
      )
    ),
    extractionPrompt  = "Extract test facts",
    changeDescription = None,
  )

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — proposes new schema with version 1") {
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> SchemaRepository.live).build
        repo     = repoEnv.get[SchemaRepository]
        schema  <- repo.create(testProposal).provideEnvironment(ZEnvironment(sharedPool))
      yield schema
    }
    result.domain        shouldBe "health"
    result.entityType    shouldBe "test_entity"
    result.schemaVersion shouldBe 1
    result.isActive      shouldBe true
    result.id            should not be null
  }

  test("findById — returns the created schema") {
    val (created, found) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> SchemaRepository.live).build
        repo     = repoEnv.get[SchemaRepository]
        created <- repo.create(testProposal).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found)
    }
    found.isDefined      shouldBe true
    found.get.id         shouldBe created.id
    found.get.entityType shouldBe "test_entity"
  }

  test("findCurrent — returns active schema for seeded health/insurance_card pair") {
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> SchemaRepository.live).build
        repo     = repoEnv.get[SchemaRepository]
        schema  <- repo.findCurrent("health", "insurance_card").provideEnvironment(ZEnvironment(sharedPool))
      yield schema
    }
    result.isDefined      shouldBe true
    result.get.domain     shouldBe "health"
    result.get.entityType shouldBe "insurance_card"
    result.get.isActive   shouldBe true
  }

  test("listCurrent(None) — returns at least 4 seeded schemas") {
    val schemas = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> SchemaRepository.live).build
        repo     = repoEnv.get[SchemaRepository]
        list    <- repo.listCurrent(None).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    schemas.size should be >= 4
  }

  test("listCurrent(Some(\"health\")) — returns schemas for health domain only") {
    val schemas = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> SchemaRepository.live).build
        repo     = repoEnv.get[SchemaRepository]
        list    <- repo.listCurrent(Some("health")).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    schemas should not be empty
    schemas.forall(_.domain == "health") shouldBe true
  }

  test("deactivate — sets isActive=false and subsequent findCurrent returns None") {
    val (deactivated, foundAfter) = run {
      for
        repoEnv    <- (ZLayer.succeed(sharedPool) >>> SchemaRepository.live).build
        repo        = repoEnv.get[SchemaRepository]
        created    <- repo.create(testProposal).provideEnvironment(ZEnvironment(sharedPool))
        deactivated <- repo.deactivate(created.id).provideEnvironment(ZEnvironment(sharedPool))
        foundAfter  <- repo.findCurrent("health", "test_entity").provideEnvironment(ZEnvironment(sharedPool))
      yield (deactivated, foundAfter)
    }
    deactivated shouldBe true
    foundAfter  shouldBe None
  }
