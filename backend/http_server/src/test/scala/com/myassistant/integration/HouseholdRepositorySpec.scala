package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{HouseholdRepository, PersonRepository}
import com.myassistant.domain.{CreateHousehold, CreatePerson, Gender, UpdateHousehold}
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import scala.compiletime.uninitialized
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.util.UUID

class HouseholdRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  test("create — inserts household and returns it with a generated UUID") {
    val result = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        repo     = repoEnv.get[HouseholdRepository]
        h       <- repo.create(CreateHousehold("Test Family")).provideEnvironment(ZEnvironment(sharedPool))
      yield h
    }
    result.name shouldBe "Test Family"
    result.id   should not be null
  }

  test("findById — returns the inserted household") {
    val (created, found) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        repo     = repoEnv.get[HouseholdRepository]
        created <- repo.create(CreateHousehold("FindById Family")).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found)
    }
    found.isDefined shouldBe true
    found.get.id    shouldBe created.id
    found.get.name  shouldBe "FindById Family"
  }

  test("listAll — returns all households") {
    val households = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        repo     = repoEnv.get[HouseholdRepository]
        _       <- repo.create(CreateHousehold("Alpha Family")).provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateHousehold("Beta Family")).provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.listAll.provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    households.size should be >= 2
    households.map(_.name) should contain allOf ("Alpha Family", "Beta Family")
  }

  test("update — modifies name and returns updated record") {
    val updated = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        repo     = repoEnv.get[HouseholdRepository]
        created <- repo.create(CreateHousehold("Old Name")).provideEnvironment(ZEnvironment(sharedPool))
        updated <- repo.update(created.id, UpdateHousehold(name = Some("New Name"))).provideEnvironment(ZEnvironment(sharedPool))
      yield updated
    }
    updated.isDefined shouldBe true
    updated.get.name  shouldBe "New Name"
  }

  test("delete — removes household from database") {
    val (deleted, found) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        repo     = repoEnv.get[HouseholdRepository]
        created <- repo.create(CreateHousehold("Temp Household")).provideEnvironment(ZEnvironment(sharedPool))
        deleted <- repo.delete(created.id).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (deleted, found)
    }
    deleted shouldBe true
    found   shouldBe None
  }

  test("addMember — inserts person_household row") {
    val membership = run {
      for
        personEnv    <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        personRepo    = personEnv.get[PersonRepository]
        hRepo         = householdEnv.get[HouseholdRepository]
        person       <- personRepo.create(CreatePerson("AddMember Person", Gender.Male, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        household    <- hRepo.create(CreateHousehold("AddMember Household")).provideEnvironment(ZEnvironment(sharedPool))
        membership   <- hRepo.addMember(person.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
      yield membership
    }
    membership.personId    should not be null
    membership.householdId should not be null
  }

  test("listMembers — returns members of a household") {
    val members = run {
      for
        personEnv    <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        personRepo    = personEnv.get[PersonRepository]
        hRepo         = householdEnv.get[HouseholdRepository]
        p1           <- personRepo.create(CreatePerson("Member One", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        p2           <- personRepo.create(CreatePerson("Member Two", Gender.Male,   None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        household    <- hRepo.create(CreateHousehold("ListMembers Household")).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(p1.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(p2.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
        members      <- hRepo.listMembers(household.id).provideEnvironment(ZEnvironment(sharedPool))
      yield members
    }
    members.size should be >= 2
    members.forall(_.householdId != null) shouldBe true
  }

  test("removeMember — removes the membership") {
    val (removed, membersAfter) = run {
      for
        personEnv    <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        personRepo    = personEnv.get[PersonRepository]
        hRepo         = householdEnv.get[HouseholdRepository]
        person       <- personRepo.create(CreatePerson("Remove Member Person", Gender.Female, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        household    <- hRepo.create(CreateHousehold("RemoveMember Household")).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(person.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
        removed      <- hRepo.removeMember(person.id, household.id).provideEnvironment(ZEnvironment(sharedPool))
        membersAfter <- hRepo.listMembers(household.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (removed, membersAfter)
    }
    removed      shouldBe true
    membersAfter shouldBe List.empty
  }

  test("listPersonHouseholds — returns all households for a person") {
    val personHouseholds = run {
      for
        personEnv    <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        personRepo    = personEnv.get[PersonRepository]
        hRepo         = householdEnv.get[HouseholdRepository]
        person       <- personRepo.create(CreatePerson("Multi Household Person", Gender.Male, None, None, None)).provideEnvironment(ZEnvironment(sharedPool))
        h1           <- hRepo.create(CreateHousehold("PersonHouseholds H1")).provideEnvironment(ZEnvironment(sharedPool))
        h2           <- hRepo.create(CreateHousehold("PersonHouseholds H2")).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(person.id, h1.id).provideEnvironment(ZEnvironment(sharedPool))
        _            <- hRepo.addMember(person.id, h2.id).provideEnvironment(ZEnvironment(sharedPool))
        pHouseholds  <- hRepo.listPersonHouseholds(person.id).provideEnvironment(ZEnvironment(sharedPool))
      yield pHouseholds
    }
    personHouseholds.size should be >= 2
    personHouseholds.forall(_.personId != null) shouldBe true
  }
