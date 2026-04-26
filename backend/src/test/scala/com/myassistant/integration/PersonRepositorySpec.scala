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
import org.scalatest.Outcome
import scala.compiletime.uninitialized
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*

class PersonRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  test("create — inserts a person and returns it with a generated UUID") {
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        person  <- repo.create(CreatePerson("Ravi Aggarwal", Gender.Male, None, Some("Ravi"), None))
                     .provideEnvironment(ZEnvironment(sharedPool))
      yield person
    }
    result.fullName      shouldBe "Ravi Aggarwal"
    result.id            should not be null
    result.gender        shouldBe Gender.Male
    result.preferredName shouldBe Some("Ravi")
  }

  test("findById — returns the inserted person") {
    val (created, found) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        created <- repo.create(CreatePerson("Nirmala Devi", Gender.Female, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found)
    }
    found.isDefined    shouldBe true
    found.get.id       shouldBe created.id
    found.get.fullName shouldBe "Nirmala Devi"
  }

  test("listAll — returns all persons") {
    val persons = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        _       <- repo.create(CreatePerson("Alice",   Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreatePerson("Bob",     Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreatePerson("Charlie", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.listAll(None).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    persons.size should be >= 3
    persons.map(_.fullName) should contain allOf ("Alice", "Bob", "Charlie")
  }

  test("update — modifies fields and returns the updated record") {
    val updated = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        created <- repo.create(CreatePerson("OldName", Gender.Male, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        updated <- repo.update(
                     created.id,
                     UpdatePerson(fullName = Some("NewName"), gender = None, dateOfBirth = None,
                                  preferredName = Some("Nick"), userIdentifier = None)
                   ).provideEnvironment(ZEnvironment(sharedPool))
      yield updated
    }
    updated.isDefined         shouldBe true
    updated.get.fullName      shouldBe "NewName"
    updated.get.preferredName shouldBe Some("Nick")
  }

  test("delete — removes person from database") {
    val (deleted, found) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        created <- repo.create(CreatePerson("TempPerson", Gender.Female, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        deleted <- repo.delete(created.id).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (deleted, found)
    }
    deleted shouldBe true
    found   shouldBe None
  }

  test("delete — fails with ReferentialIntegrityError when relationships exist") {
    val deleteResult = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv    <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        personRepo = personEnv.get[PersonRepository]
        relRepo    = relEnv.get[RelationshipRepository]
        person1   <- personRepo.create(CreatePerson("PersonA", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        person2   <- personRepo.create(CreatePerson("PersonB", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        _         <- relRepo.create(CreateRelationship(person1.id, person2.id, RelationType.Wife)).provideEnvironment(ZEnvironment(sharedPool))
        delResult <- personRepo.delete(person1.id).provideEnvironment(ZEnvironment(sharedPool)).exit
      yield delResult
    }
    deleteResult.isFailure shouldBe true
    val appError: Option[AppError] = deleteResult match
      case zio.Exit.Failure(cause) => cause.failureOption
      case _                       => None
    appError.isDefined shouldBe true
    appError.get       shouldBe a [AppError.ReferentialIntegrityError]
  }
