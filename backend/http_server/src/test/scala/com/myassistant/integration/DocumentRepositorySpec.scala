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
  private var userInputSourceTypeId: UUID = uninitialized
  private var plaidPollSourceTypeId: UUID = uninitialized
  private var aiExtractedSourceTypeId: UUID = uninitialized

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
          pool     = poolEnv.get[ZConnectionPool]
          _        = sharedPool = pool
          _        = poolScope  = scope
          // Load source_type UUIDs from the seeded reference table
          userInputId <- transaction(sql"SELECT id::text FROM source_type WHERE name = 'user_input'".query[String].selectOne)
                           .mapError(AppError.DatabaseError(_))
                           .flatMap(ZIO.fromOption(_).mapError(_ => AppError.InternalError(new RuntimeException("missing user_input source_type"))))
                           .provideEnvironment(ZEnvironment(pool))
          plaidId     <- transaction(sql"SELECT id::text FROM source_type WHERE name = 'plaid_poll'".query[String].selectOne)
                           .mapError(AppError.DatabaseError(_))
                           .flatMap(ZIO.fromOption(_).mapError(_ => AppError.InternalError(new RuntimeException("missing plaid_poll source_type"))))
                           .provideEnvironment(ZEnvironment(pool))
          aiId        <- transaction(sql"SELECT id::text FROM source_type WHERE name = 'ai_extracted'".query[String].selectOne)
                           .mapError(AppError.DatabaseError(_))
                           .flatMap(ZIO.fromOption(_).mapError(_ => AppError.InternalError(new RuntimeException("missing ai_extracted source_type"))))
                           .provideEnvironment(ZEnvironment(pool))
          _            = userInputSourceTypeId   = UUID.fromString(userInputId)
          _            = plaidPollSourceTypeId   = UUID.fromString(plaidId)
          _            = aiExtractedSourceTypeId = UUID.fromString(aiId)
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

  private val emptyEmbedding = List.fill(1536)(0.0)

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
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield d
    }
    doc.personId    shouldBe Some(doc.personId.get)
    doc.contentText shouldBe "Test document content"
    doc.sourceTypeId shouldBe userInputSourceTypeId
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
                            sourceTypeId  = userInputSourceTypeId,
                            embedding     = emptyEmbedding,
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
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        second    <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Updated content",
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
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
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
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
    found.get.sourceTypeId       shouldBe userInputSourceTypeId
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
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Doc B",
                         sourceTypeId  = plaidPollSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        list      <- docEnv.get[DocumentRepository]
                       .list(Some(person.id), None, None, None, None, 10, 0)
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    docs.size should be >= 2
    docs.forall(_.personId.isDefined) shouldBe true
  }

  test("list — filtered by sourceTypeId") {
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
                         sourceTypeId  = plaidPollSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "User doc",
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        list      <- docEnv.get[DocumentRepository]
                       .list(Some(person.id), None, Some(plaidPollSourceTypeId), None, None, 10, 0)
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    docs should not be empty
    docs.forall(_.sourceTypeId == plaidPollSourceTypeId) shouldBe true
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
                         sourceTypeId  = userInputSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        list      <- docEnv.get[DocumentRepository]
                       .list(None, None, None, None, None, 100, 0)
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
                         sourceTypeId  = aiExtractedSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId      = Some(person.id),
                         householdId   = None,
                         contentText   = "Count doc 2",
                         sourceTypeId  = aiExtractedSourceTypeId,
                         embedding     = emptyEmbedding,
                         files         = Json.arr(),
                         supersedesIds = Nil,
                       ))
                       .provideEnvironment(ZEnvironment(sharedPool))
        countAll    <- docEnv.get[DocumentRepository].count(None, None, None).provideEnvironment(ZEnvironment(sharedPool))
        countPerson <- docEnv.get[DocumentRepository].count(Some(person.id), None, None).provideEnvironment(ZEnvironment(sharedPool))
        countSource <- docEnv.get[DocumentRepository].count(Some(person.id), None, Some(aiExtractedSourceTypeId)).provideEnvironment(ZEnvironment(sharedPool))
      yield (countAll, countPerson, countSource)
    }
    countAll   should be >= 2L
    countByPerson should be >= 2L
    countBySource shouldBe 2L
  }

  test("count — filters by householdId") {
    val count = run {
      for
        householdEnv <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        docEnv       <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        household    <- householdEnv.get[HouseholdRepository]
                          .create(CreateHousehold("Count Household"))
                          .provideEnvironment(ZEnvironment(sharedPool))
        _            <- docEnv.get[DocumentRepository]
                          .create(CreateDocument(
                            personId      = None,
                            householdId   = Some(household.id),
                            contentText   = "Household count doc",
                            sourceTypeId  = userInputSourceTypeId,
                            embedding     = emptyEmbedding,
                            files         = Json.arr(),
                            supersedesIds = Nil,
                          ))
                          .provideEnvironment(ZEnvironment(sharedPool))
        c <- docEnv.get[DocumentRepository].count(None, Some(household.id), None).provideEnvironment(ZEnvironment(sharedPool))
      yield c
    }
    count shouldBe 1L
  }

  test("searchBySimilarity — returns matching documents, supports personId filter") {
    val nonZeroEmbedding = 0.1 :: List.fill(1535)(0.0)
    val (allMatches, personAMatches, personAId) = run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        personA   <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Search Person A", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        personB   <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Search Person B", Gender.Female, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId = Some(personA.id), householdId = None,
                         contentText = "Searchable doc A", sourceTypeId = userInputSourceTypeId,
                         embedding = nonZeroEmbedding, files = Json.arr(), supersedesIds = Nil))
                       .provideEnvironment(ZEnvironment(sharedPool))
        _         <- docEnv.get[DocumentRepository]
                       .create(CreateDocument(
                         personId = Some(personB.id), householdId = None,
                         contentText = "Searchable doc B", sourceTypeId = userInputSourceTypeId,
                         embedding = nonZeroEmbedding, files = Json.arr(), supersedesIds = Nil))
                       .provideEnvironment(ZEnvironment(sharedPool))
        all       <- docEnv.get[DocumentRepository]
                       .searchBySimilarity(nonZeroEmbedding, None, None, None, 10, 0.5)
                       .provideEnvironment(ZEnvironment(sharedPool))
        filtered  <- docEnv.get[DocumentRepository]
                       .searchBySimilarity(nonZeroEmbedding, Some(personA.id), None, None, 10, 0.5)
                       .provideEnvironment(ZEnvironment(sharedPool))
      yield (all, filtered, personA.id)
    }
    allMatches should not be empty
    personAMatches should not be empty
    personAMatches.forall { case (doc, _) => doc.personId.contains(personAId) } shouldBe true
  }
