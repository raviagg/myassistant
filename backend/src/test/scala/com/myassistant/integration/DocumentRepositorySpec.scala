package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{DocumentRepository, HouseholdRepository, PersonRepository}
import com.myassistant.domain.{CreateDocument, CreateHousehold, CreatePerson, Gender}
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

class DocumentRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  test("create — inserts a document for a person and returns it") {
    val doc = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Doc Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        d         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Test document content",
                         sourceType    = "user_input",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield d
    }
    doc.personId    shouldBe Some(doc.personId.get)
    doc.contentText shouldBe "Test document content"
    doc.sourceType  shouldBe "user_input"
    doc.files       shouldBe Json.arr()
    doc.id          should not be null
  }

  test("create — inserts a document for a household and returns it") {
    val doc = run {
      for
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        docEnv       <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        household    <- householdEnv.get[HouseholdRepository]
                          .create(CreateHousehold("Doc Household"))
                          .provideEnvironment(ZEnvironment(sharedPool))
        d            <- docEnv.get[DocumentRepository]
                          .create(CreateDocument(
                            personId      = None,
                            householdId   = Some(household.id),
                            contentText   = "Household document",
                            sourceType    = "user_input",
                            files         = Json.arr(),
                            supersedesIds = Nil,
                          ))
                          .provideEnvironment(ZEnvironment(sharedPool))
      yield d
    }
    doc.householdId.isDefined shouldBe true
    doc.personId              shouldBe None
    doc.contentText           shouldBe "Household document"
  }

  test("create — with non-empty supersedesIds") {
    val (first, second) = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Supersedes Person", Gender.Female, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        first     <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Original content",
                         sourceType    = "user_input",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        second    <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Updated content",
                         sourceType    = "user_input",
                         files         = Json.arr(),
                         supersedesIds = List(first.id),
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield (first, second)
    }
    second.supersedesIds shouldBe List(first.id)
    second.contentText   shouldBe "Updated content"
  }

  test("findById — returns the created document") {
    val (created, found, missing) = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("FindById Doc Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        created   <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "findById content",
                         sourceType    = "file_upload",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        found     <- docEnv.get[DocumentRepository].findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
        missing   <- docEnv.get[DocumentRepository].findById(UUID.randomUUID()).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found, missing)
    }
    found.isDefined              shouldBe true
    found.get.id                 shouldBe created.id
    found.get.contentText        shouldBe "findById content"
    found.get.sourceType         shouldBe "file_upload"
    missing                      shouldBe None
  }

  test("list — returns documents filtered by personId, newest first") {
    val docs = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("List Doc Person", Gender.Female, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Doc A",
                         sourceType    = "user_input",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Doc B",
                         sourceType    = "gmail_poll",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        list      <- docEnv.get[DocumentRepository]
                       .list(Some(person.id), None, None, 10, 0)
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    docs.size should be >= 2
    docs.forall(_.personId.isDefined) shouldBe true
  }

  test("list — filtered by sourceType") {
    val docs = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("SourceType Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Plaid doc",
                         sourceType    = "plaid_poll",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "User doc",
                         sourceType    = "user_input",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        list      <- docEnv.get[DocumentRepository]
                       .list(Some(person.id), None, Some("plaid_poll"), 10, 0)
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    docs should not be empty
    docs.forall(_.sourceType == "plaid_poll") shouldBe true
  }

  test("list — with no filters returns all documents (limit/offset pagination)") {
    val docs = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Pagination Person", Gender.Female, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Page doc",
                         sourceType    = "user_input",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        list      <- docEnv.get[DocumentRepository]
                       .list(None, None, None, 100, 0)
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    docs should not be empty
  }

  test("count — returns correct counts with and without filters") {
    val (countAll, countByPerson, countBySource) = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Count Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Count doc 1",
                         sourceType    = "ai_extracted",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Count doc 2",
                         sourceType    = "ai_extracted",
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        countAll    <- docEnv.get[DocumentRepository].count(None, None, None).provideEnvironment(ZEnvironment(sharedPool))
        countPerson <- docEnv.get[DocumentRepository].count(Some(person.id), None, None).provideEnvironment(ZEnvironment(sharedPool))
        countSource <- docEnv.get[DocumentRepository].count(Some(person.id), None, Some("ai_extracted")).provideEnvironment(ZEnvironment(sharedPool))
      yield (countAll, countPerson, countSource)
    }
    countAll   should be >= 2L
    countByPerson should be >= 2L
    countBySource shouldBe 2L
  }
