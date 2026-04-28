package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{HouseholdRepository, PersonRepository, RelationshipRepository}
import com.myassistant.domain.{CreateHousehold, CreatePerson, CreateRelationship, Gender, RelationType, UpdatePerson}
import com.myassistant.errors.AppError
import java.time.LocalDate
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

  test("search(None,...) — returns all persons when no filters applied") {
    val persons = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        _       <- repo.create(CreatePerson("Alice",   Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreatePerson("Bob",     Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreatePerson("Charlie", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.search(None, None, None, None, None, None, 100, 0).provideEnvironment(ZEnvironment(sharedPool))
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

  test("create — with dateOfBirth and userIdentifier set") {
    val dob = LocalDate.of(1990, 6, 15)
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        person  <- repo.create(CreatePerson("Full Person", Gender.Female, Some(dob), Some("Full"), Some("full.person@test.com")))
                     .provideEnvironment(ZEnvironment(sharedPool))
      yield person
    }
    result.dateOfBirth    shouldBe Some(dob)
    result.userIdentifier shouldBe Some("full.person@test.com")
    result.preferredName  shouldBe Some("Full")
    result.gender         shouldBe Gender.Female
  }

  test("findByUserIdentifier — returns person by identifier and None for missing") {
    val (found, missing) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        _       <- repo.create(CreatePerson("Identifier Person", Gender.Male, None, None, Some("identifier.person@test.com")))
                     .provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findByUserIdentifier("identifier.person@test.com").provideEnvironment(ZEnvironment(sharedPool))
        missing <- repo.findByUserIdentifier("nobody@test.com").provideEnvironment(ZEnvironment(sharedPool))
      yield (found, missing)
    }
    found.isDefined           shouldBe true
    found.get.userIdentifier  shouldBe Some("identifier.person@test.com")
    missing                   shouldBe None
  }

  test("create — fails with Conflict when userIdentifier is duplicated") {
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        _       <- repo.create(CreatePerson("Original", Gender.Male, None, None, Some("dup@test.com")))
                     .provideEnvironment(ZEnvironment(sharedPool))
        conflict <- repo.create(CreatePerson("Duplicate", Gender.Female, None, None, Some("dup@test.com")))
                      .provideEnvironment(ZEnvironment(sharedPool))
                      .exit
      yield conflict
    }
    result.isFailure shouldBe true
    val appError: Option[AppError] = result match
      case zio.Exit.Failure(cause) => cause.failureOption
      case _                       => None
    appError.isDefined shouldBe true
    appError.get       shouldBe a [AppError.Conflict]
  }

  test("search — filtered by householdId returns only household members") {
    val members = run {
      for
        personEnv    <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        personRepo    = personEnv.get[PersonRepository]
        hRepo         = householdEnv.get[HouseholdRepository]
        p1           <- personRepo.create(CreatePerson("HH Member One", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        p2           <- personRepo.create(CreatePerson("HH Member Two", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        _            <- personRepo.create(CreatePerson("Not In HH",     Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        household    <- hRepo.create(CreateHousehold("Search Household")).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(p1.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(p2.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
        list         <- personRepo.search(None, None, None, None, None, Some(household.id), 100, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    members.size shouldBe 2
    members.map(_.fullName) should contain allOf ("HH Member One", "HH Member Two")
  }

  test("update — covers dateOfBirth and gender update paths") {
    val updated = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        created <- repo.create(CreatePerson("UpdatePaths", Gender.Male, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        updated <- repo.update(
                     created.id,
                     UpdatePerson(fullName = None, gender = Some(Gender.Female),
                                  dateOfBirth = Some(LocalDate.of(1985, 3, 20)),
                                  preferredName = None, userIdentifier = None)
                   ).provideEnvironment(ZEnvironment(sharedPool))
      yield updated
    }
    updated.isDefined        shouldBe true
    updated.get.gender       shouldBe Gender.Female
    updated.get.dateOfBirth  shouldBe Some(LocalDate.of(1985, 3, 20))
  }

  test("update — with empty patch delegates to findById") {
    val updated = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        created <- repo.create(CreatePerson("EmptyPatch", Gender.Male, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        updated <- repo.update(
                     created.id,
                     UpdatePerson(fullName = None, gender = None, dateOfBirth = None,
                                  preferredName = None, userIdentifier = None)
                   ).provideEnvironment(ZEnvironment(sharedPool))
      yield updated
    }
    updated.isDefined        shouldBe true
    updated.get.fullName     shouldBe "EmptyPatch"
  }

  test("search — with name and gender filters covers filter lambdas and reduce path") {
    val results = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        _       <- repo.create(CreatePerson("Alice Finder", Gender.Female, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreatePerson("Bob Irrelevant", Gender.Male, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.search(Some("Alice"), Some("female"), None, None, None, None, 10, 0)
                     .provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    results should not be empty
    results.forall(_.fullName.contains("Alice")) shouldBe true
  }

  test("update — updates userIdentifier field") {
    val updated = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        repo     = repoEnv.get[PersonRepository]
        created <- repo.create(CreatePerson("UpdateUID", Gender.Male, None, None, None))
                     .provideEnvironment(ZEnvironment(sharedPool))
        updated <- repo.update(
                     created.id,
                     UpdatePerson(fullName = None, gender = None, dateOfBirth = None,
                                  preferredName = None, userIdentifier = Some("new-uid"))
                   ).provideEnvironment(ZEnvironment(sharedPool))
      yield updated
    }
    updated.isDefined                shouldBe true
    updated.get.userIdentifier       shouldBe Some("new-uid")
  }
