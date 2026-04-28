package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{PersonRepository, RelationshipRepository}
import com.myassistant.domain.{CreatePerson, CreateRelationship, Gender, Person, RelationType}
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.Outcome
import scala.compiletime.uninitialized
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.jdbc.*
import java.util.UUID

class RelationshipRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  private def mkPerson(personRepo: PersonRepository, name: String, gender: Gender = Gender.Male): ZIO[ZConnectionPool, AppError, Person] =
    personRepo.create(CreatePerson(name, gender, None, None, None))

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — inserts a father relationship and returns it") {
    val rel = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv    <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        pRepo      = personEnv.get[PersonRepository]
        rRepo      = relEnv.get[RelationshipRepository]
        father    <- mkPerson(pRepo, "Father Person").provideEnvironment(ZEnvironment(sharedPool))
        child     <- mkPerson(pRepo, "Child Person", Gender.Female).provideEnvironment(ZEnvironment(sharedPool))
        rel       <- rRepo.create(CreateRelationship(father.id, child.id, RelationType.Father)).provideEnvironment(ZEnvironment(sharedPool))
      yield rel
    }
    rel.relationType shouldBe RelationType.Father
    rel.id           should not be null
  }

  test("create — covers all relation types (mother, son, daughter, brother, sister, husband, wife)") {
    val types = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv    <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        pRepo      = personEnv.get[PersonRepository]
        rRepo      = relEnv.get[RelationshipRepository]
        persons   <- ZIO.foreach(List("PA","PB","PC","PD","PE","PF","PG","PH"))(name =>
                       mkPerson(pRepo, name).provideEnvironment(ZEnvironment(sharedPool)))
        relTypes   = List(
                       RelationType.Mother, RelationType.Son, RelationType.Daughter,
                       RelationType.Brother, RelationType.Sister, RelationType.Husband, RelationType.Wife
                     )
        rels      <- ZIO.foreach(relTypes.zipWithIndex) { (rt, i) =>
                       rRepo.create(CreateRelationship(persons(i).id, persons(i+1).id, rt))
                         .provideEnvironment(ZEnvironment(sharedPool))
                     }
      yield rels.map(_.relationType)
    }
    types shouldBe List(
      RelationType.Mother, RelationType.Son, RelationType.Daughter,
      RelationType.Brother, RelationType.Sister, RelationType.Husband, RelationType.Wife
    )
  }

  test("findById — returns existing relationship and None for missing") {
    val (found, missing) = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv    <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        pRepo      = personEnv.get[PersonRepository]
        rRepo      = relEnv.get[RelationshipRepository]
        p1        <- mkPerson(pRepo, "FindById P1").provideEnvironment(ZEnvironment(sharedPool))
        p2        <- mkPerson(pRepo, "FindById P2").provideEnvironment(ZEnvironment(sharedPool))
        created   <- rRepo.create(CreateRelationship(p1.id, p2.id, RelationType.Brother)).provideEnvironment(ZEnvironment(sharedPool))
        found     <- rRepo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
        missing   <- rRepo.findById(UUID.randomUUID()).provideEnvironment(ZEnvironment(sharedPool))
      yield (found, missing)
    }
    found.isDefined          shouldBe true
    found.get.relationType   shouldBe RelationType.Brother
    missing                  shouldBe None
  }

  test("findByPerson — returns relationships where person is on either end") {
    val rels = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv    <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        pRepo      = personEnv.get[PersonRepository]
        rRepo      = relEnv.get[RelationshipRepository]
        hub       <- mkPerson(pRepo, "Hub Person").provideEnvironment(ZEnvironment(sharedPool))
        spoke1    <- mkPerson(pRepo, "Spoke One").provideEnvironment(ZEnvironment(sharedPool))
        spoke2    <- mkPerson(pRepo, "Spoke Two").provideEnvironment(ZEnvironment(sharedPool))
        _         <- rRepo.create(CreateRelationship(hub.id, spoke1.id, RelationType.Son)).provideEnvironment(ZEnvironment(sharedPool))
        _         <- rRepo.create(CreateRelationship(spoke2.id, hub.id, RelationType.Mother)).provideEnvironment(ZEnvironment(sharedPool))
        list      <- rRepo.findByPerson(hub.id).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    rels.size should be >= 2
    rels.forall(r => r.fromPersonId != null && r.toPersonId != null) shouldBe true
  }

  test("findByPerson — returns empty list for person with no relationships") {
    val rels = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv    <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        loner     <- mkPerson(personEnv.get[PersonRepository], "Loner Person").provideEnvironment(ZEnvironment(sharedPool))
        list      <- relEnv.get[RelationshipRepository].findByPerson(loner.id).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    rels shouldBe List.empty
  }

  test("delete — removes relationship and returns true; returns false for missing id") {
    val (deleted, foundAfter, missingDelete) = run {
      for
        personEnv  <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        relEnv     <- (ZLayer.succeed(sharedPool) >>> RelationshipRepository.live).build
        pRepo       = personEnv.get[PersonRepository]
        rRepo       = relEnv.get[RelationshipRepository]
        p1         <- mkPerson(pRepo, "Delete P1").provideEnvironment(ZEnvironment(sharedPool))
        p2         <- mkPerson(pRepo, "Delete P2").provideEnvironment(ZEnvironment(sharedPool))
        created    <- rRepo.create(CreateRelationship(p1.id, p2.id, RelationType.Sister)).provideEnvironment(ZEnvironment(sharedPool))
        deleted    <- rRepo.delete(created.id).provideEnvironment(ZEnvironment(sharedPool))
        foundAfter <- rRepo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
        noDelete   <- rRepo.delete(UUID.randomUUID()).provideEnvironment(ZEnvironment(sharedPool))
      yield (deleted, foundAfter, noDelete)
    }
    deleted       shouldBe true
    foundAfter    shouldBe None
    missingDelete shouldBe false
  }
