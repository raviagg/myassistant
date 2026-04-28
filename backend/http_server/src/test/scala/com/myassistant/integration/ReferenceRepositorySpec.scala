package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.ReferenceRepository
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import scala.compiletime.uninitialized
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*

class ReferenceRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("listDomains — returns at least 7 seeded domains") {
    val domains = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> ReferenceRepository.live).build
        repo     = repoEnv.get[ReferenceRepository]
        list    <- repo.listDomains.provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    domains.size should be >= 7
  }

  test("listSourceTypes — returns at least 5 seeded source types") {
    val sourceTypes = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> ReferenceRepository.live).build
        repo     = repoEnv.get[ReferenceRepository]
        list    <- repo.listSourceTypes.provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    sourceTypes.size should be >= 5
  }

  test("createDomain — inserts a new domain and returns it") {
    val domain = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> ReferenceRepository.live).build
        repo     = repoEnv.get[ReferenceRepository]
        d       <- repo.createDomain("test_domain_ref", "A test domain for reference tests").provideEnvironment(ZEnvironment(sharedPool))
      yield d
    }
    domain.name        shouldBe "test_domain_ref"
    domain.description shouldBe "A test domain for reference tests"
  }

  test("createSourceType — inserts a new source type and returns it") {
    val sourceType = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> ReferenceRepository.live).build
        repo     = repoEnv.get[ReferenceRepository]
        st      <- repo.createSourceType("test_source_ref", "A test source type").provideEnvironment(ZEnvironment(sharedPool))
      yield st
    }
    sourceType.name        shouldBe "test_source_ref"
    sourceType.description shouldBe "A test source type"
  }

  test("listKinshipAliases(None) — returns a list without error") {
    val aliases = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> ReferenceRepository.live).build
        repo     = repoEnv.get[ReferenceRepository]
        list    <- repo.listKinshipAliases(None).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    aliases should not be null
  }

  test("listKinshipAliases(Some(\"hindi\")) — filtered list without error") {
    val aliases = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> ReferenceRepository.live).build
        repo     = repoEnv.get[ReferenceRepository]
        list    <- repo.listKinshipAliases(Some("hindi")).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    aliases should not be null
  }
