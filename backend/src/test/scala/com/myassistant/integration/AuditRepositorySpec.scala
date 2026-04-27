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
import org.scalatest.Outcome
import scala.compiletime.uninitialized
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.time.Instant
import java.util.UUID

class AuditRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  test("create (job entry) — inserts and returns audit log entry") {
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
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> AuditRepository.live).build
        repo     = repoEnv.get[AuditRepository]
        r       <- repo.create(entry).provideEnvironment(ZEnvironment(sharedPool))
      yield r
    }
    result.id      shouldBe entry.id
    result.jobType shouldBe Some("plaid_poll")
    result.message shouldBe "poll result"
    result.status  shouldBe InteractionStatus.Success
  }

  test("create (person entry) — creates a person first then inserts audit log with personId") {
    val result = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        auditEnv  <- (ZLayer.succeed(sharedPool) >>> AuditRepository.live).build
        personRepo = personEnv.get[PersonRepository]
        auditRepo  = auditEnv.get[AuditRepository]
        person    <- personRepo.create(CreatePerson("Audit Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
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
        r         <- auditRepo.create(entry).provideEnvironment(ZEnvironment(sharedPool))
      yield r
    }
    result.personId.isDefined shouldBe true
    result.jobType            shouldBe None
    result.message            shouldBe "user chat message"
    result.response           shouldBe Some("agent response")
  }

  test("findById — returns by ID and None for missing") {
    val (found, missing) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> AuditRepository.live).build
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
        created <- repo.create(entry).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
        missing <- repo.findById(UUID.randomUUID()).provideEnvironment(ZEnvironment(sharedPool))
      yield (found, missing)
    }
    found.isDefined   shouldBe true
    found.get.jobType shouldBe Some("gmail_poll")
    found.get.status  shouldBe InteractionStatus.Partial
    missing           shouldBe None
  }

  test("listByJobType — returns entries for a job type using limit/offset") {
    val entries = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> AuditRepository.live).build
        repo     = repoEnv.get[AuditRepository]
        e1       = AuditLog(UUID.randomUUID(), None, Some("plaid_poll"), "msg 1", None, Json.arr(), InteractionStatus.Success, None, Instant.now())
        e2       = AuditLog(UUID.randomUUID(), None, Some("plaid_poll"), "msg 2", None, Json.arr(), InteractionStatus.Failed,  Some("err"), Instant.now())
        e3       = AuditLog(UUID.randomUUID(), None, Some("gmail_poll"), "msg 3", None, Json.arr(), InteractionStatus.Success, None, Instant.now())
        _       <- repo.create(e1).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(e2).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(e3).provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.listByJobType("plaid_poll", 10, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    entries.size should be >= 2
    entries.forall(_.jobType.contains("plaid_poll")) shouldBe true
  }

  test("listByPerson — returns entries for a person using limit/offset") {
    val entries = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        auditEnv  <- (ZLayer.succeed(sharedPool) >>> AuditRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("ListByPerson Audit", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        auditRepo  = auditEnv.get[AuditRepository]
        e1         = AuditLog(UUID.randomUUID(), Some(person.id), None, "chat msg 1", Some("resp 1"), Json.arr(), InteractionStatus.Success, None, Instant.now())
        e2         = AuditLog(UUID.randomUUID(), Some(person.id), None, "chat msg 2", None,           Json.arr(), InteractionStatus.Partial,  Some("partial"), Instant.now())
        _         <- auditRepo.create(e1).provideEnvironment(ZEnvironment(sharedPool))
        _         <- auditRepo.create(e2).provideEnvironment(ZEnvironment(sharedPool))
        list      <- auditRepo.listByPerson(person.id, 10, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    entries.size should be >= 2
    entries.forall(_.personId.isDefined) shouldBe true
  }
