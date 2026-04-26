package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{DocumentRepository, FactRepository, PersonRepository}
import com.myassistant.domain.{CreateDocument, CreateFact, CreatePerson, Gender, OperationType}
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

class FactRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

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

  private def seedPrerequisites(): (UUID, UUID) =
    run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Fact Test Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        schemaIdStr <- transaction(
                         sql"SELECT id::text FROM entity_type_schema LIMIT 1"
                           .query[String].selectOne
                       ).mapError(AppError.DatabaseError(_))
                        .flatMap(ZIO.fromOption(_).mapError(_ =>
                          AppError.InternalError(new RuntimeException("No schema seed found"))))
                        .provideEnvironment(ZEnvironment(sharedPool))
        schemaId     = UUID.fromString(schemaIdStr)
        doc         <- docEnv.get[DocumentRepository]
                         .create(CreateDocument(
                           personId      = Some(person.id),
                           householdId   = None,
                           contentText   = "Test document for fact tests",
                           sourceType    = "user_input",
                           files         = Json.arr(),
                           supersedesIds = Nil,
                         ))
                         .provideEnvironment(ZEnvironment(sharedPool))
      yield (doc.id, schemaId)
    }

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("create — inserts a fact row and returns it with a generated UUID") {
    val (docId, schemaId) = seedPrerequisites()
    val fact = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        result  <- repo.create(CreateFact(
                     documentId       = docId,
                     schemaId         = schemaId,
                     entityInstanceId = None,
                     operationType    = OperationType.Create,
                     fields           = Json.obj(
                       "title"  -> Json.fromString("Test task"),
                       "status" -> Json.fromString("open"),
                     ),
                   )).provideEnvironment(ZEnvironment(sharedPool))
      yield result
    }
    fact.id            should not be null
    fact.documentId    shouldBe docId
    fact.schemaId      shouldBe schemaId
    fact.operationType shouldBe OperationType.Create
  }

  test("findByEntityInstance — returns all facts for an entity instance in creation order") {
    val (docId, schemaId) = seedPrerequisites()
    val entityId          = UUID.randomUUID()
    val facts = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, Some(entityId), OperationType.Create,
                     Json.obj("title"  -> Json.fromString("Renew passport"),
                              "status" -> Json.fromString("open"))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, Some(entityId), OperationType.Update,
                     Json.obj("status" -> Json.fromString("in_progress"))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.findByEntityInstance(entityId).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    facts.size                             shouldBe 2
    facts.map(_.entityInstanceId).distinct shouldBe List(entityId)
    facts.map(_.operationType).toSet should contain (OperationType.Create)
    facts.map(_.operationType).toSet should contain (OperationType.Update)
  }

  test("findByDocument — returns all facts for a document") {
    val (docId, schemaId) = seedPrerequisites()
    val facts = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                     Json.obj("employer"     -> Json.fromString("Acme"),
                              "pay_period"   -> Json.fromString("2024-01-31"),
                              "gross_income" -> Json.fromInt(10000))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                     Json.obj("employer"     -> Json.fromString("Beta Corp"),
                              "pay_period"   -> Json.fromString("2024-02-29"),
                              "gross_income" -> Json.fromInt(11000))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.findByDocument(docId).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    facts.size                       should be >= 2
    facts.map(_.documentId).distinct shouldBe List(docId)
  }

  test("findById — returns the fact when it exists and None for missing") {
    val (docId, schemaId) = seedPrerequisites()
    val (created, found, missing) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        created <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                     Json.obj("title" -> Json.fromString("findById fact"))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
        missing <- repo.findById(java.util.UUID.randomUUID()).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found, missing)
    }
    found.isDefined         shouldBe true
    found.get.id            shouldBe created.id
    found.get.operationType shouldBe OperationType.Create
    missing                 shouldBe None
  }

  test("create — stores a Delete operation fact and findById returns OperationType.Delete") {
    val (docId, schemaId) = seedPrerequisites()
    val (fact, found) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        created <- repo.create(CreateFact(
                     docId, schemaId, Some(UUID.randomUUID()), OperationType.Delete,
                     Json.obj("reason" -> Json.fromString("superseded")),
                   )).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found)
    }
    fact.operationType  shouldBe OperationType.Delete
    found.isDefined     shouldBe true
    found.get.operationType shouldBe OperationType.Delete
  }

  test("findBySchema — returns facts for a schema version with pagination") {
    val (docId, schemaId) = seedPrerequisites()
    val facts = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, None, OperationType.Create,
                     Json.obj("status" -> Json.fromString("open"))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, None, OperationType.Update,
                     Json.obj("status" -> Json.fromString("done"))))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.findBySchema(schemaId, 10, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    facts.size should be >= 2
    facts.forall(_.schemaId == schemaId) shouldBe true
  }
