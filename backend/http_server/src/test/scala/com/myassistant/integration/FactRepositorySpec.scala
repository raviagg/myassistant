package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.{DocumentRepository, FactRepository, HouseholdRepository, PersonRepository}
import com.myassistant.domain.{CreateDocument, CreateFact, CreateHousehold, CreatePerson, Gender, OperationType}
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

  private val emptyEmbedding = List.fill(1536)(0.0)

  // Returns (docId, schemaId, personId, domainId, entityType)
  private def seedFull(): (UUID, UUID, UUID, UUID, String) =
    run {
      for
        personEnv <- (ZLayer.succeed(sharedPool) >>> PersonRepository.live).build
        docEnv    <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        person    <- personEnv.get[PersonRepository]
                       .create(CreatePerson("Fact Test Person", Gender.Male, None, None, None))
                       .provideEnvironment(ZEnvironment(sharedPool))
        schemaRow   <- transaction(
                         sql"SELECT id::text, domain_id::text, entity_type FROM entity_type_schema LIMIT 1"
                           .query[(String, String, String)].selectOne
                       ).mapError(AppError.DatabaseError(_))
                        .flatMap(ZIO.fromOption(_).mapError(_ =>
                          AppError.InternalError(new RuntimeException("No schema seed found"))))
                        .provideEnvironment(ZEnvironment(sharedPool))
        (schemaIdStr, domainIdStr, entityType) = schemaRow
        schemaId     = UUID.fromString(schemaIdStr)
        domainId     = UUID.fromString(domainIdStr)
        sourceTypeIdStr <- transaction(
                             sql"SELECT id::text FROM source_type WHERE name = 'user_input'".query[String].selectOne
                           ).mapError(AppError.DatabaseError(_))
                            .flatMap(ZIO.fromOption(_).mapError(_ =>
                              AppError.InternalError(new RuntimeException("No user_input source_type found"))))
                            .provideEnvironment(ZEnvironment(sharedPool))
        sourceTypeId = UUID.fromString(sourceTypeIdStr)
        doc         <- docEnv.get[DocumentRepository]
                         .create(CreateDocument(
                           personId      = Some(person.id),
                           householdId   = None,
                           contentText   = "Test document for fact tests",
                           sourceTypeId  = sourceTypeId,
                           embedding     = emptyEmbedding,
                           files         = Json.arr(),
                           supersedesIds = Nil,
                         ))
                         .provideEnvironment(ZEnvironment(sharedPool))
      yield (doc.id, schemaId, person.id, domainId, entityType)
    }

  private def seedPrerequisites(): (UUID, UUID) =
    val (docId, schemaId, _, _, _) = seedFull()
    (docId, schemaId)

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
                     entityInstanceId = UUID.randomUUID(),
                     operationType    = OperationType.Create,
                     fields           = Json.obj(
                       "title"  -> Json.fromString("Test task"),
                       "status" -> Json.fromString("open"),
                     ),
                     embedding        = emptyEmbedding,
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
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Create,
                     Json.obj("title"  -> Json.fromString("Renew passport"),
                              "status" -> Json.fromString("open")),
                     emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Update,
                     Json.obj("status" -> Json.fromString("in_progress")),
                     emptyEmbedding))
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
        _       <- repo.create(CreateFact(docId, schemaId, UUID.randomUUID(), OperationType.Create,
                     Json.obj("employer"     -> Json.fromString("Acme"),
                              "pay_period"   -> Json.fromString("2024-01-31"),
                              "gross_income" -> Json.fromInt(10000)),
                     emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, UUID.randomUUID(), OperationType.Create,
                     Json.obj("employer"     -> Json.fromString("Beta Corp"),
                              "pay_period"   -> Json.fromString("2024-02-29"),
                              "gross_income" -> Json.fromInt(11000)),
                     emptyEmbedding))
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
        created <- repo.create(CreateFact(docId, schemaId, UUID.randomUUID(), OperationType.Create,
                     Json.obj("title" -> Json.fromString("findById fact")),
                     emptyEmbedding))
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
                     docId, schemaId, UUID.randomUUID(), OperationType.Delete,
                     Json.obj("reason" -> Json.fromString("superseded")),
                     emptyEmbedding,
                   )).provideEnvironment(ZEnvironment(sharedPool))
        found   <- repo.findById(created.id).provideEnvironment(ZEnvironment(sharedPool))
      yield (created, found)
    }
    fact.operationType       shouldBe OperationType.Delete
    found.isDefined          shouldBe true
    found.get.operationType  shouldBe OperationType.Delete
  }

  test("findCurrentByEntityInstance — returns current merged state, None after delete") {
    val (docId, schemaId) = seedPrerequisites()
    val entityId          = UUID.randomUUID()
    val (current, afterDelete) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Create,
                     Json.obj("title" -> Json.fromString("Buy groceries")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Update,
                     Json.obj("status" -> Json.fromString("done")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        current <- repo.findCurrentByEntityInstance(entityId).provideEnvironment(ZEnvironment(sharedPool))
        deletedEntityId = UUID.randomUUID()
        _       <- repo.create(CreateFact(docId, schemaId, deletedEntityId, OperationType.Create,
                     Json.obj("title" -> Json.fromString("Deleted task")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        _       <- repo.create(CreateFact(docId, schemaId, deletedEntityId, OperationType.Delete,
                     Json.obj(), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        afterDelete <- repo.findCurrentByEntityInstance(deletedEntityId).provideEnvironment(ZEnvironment(sharedPool))
      yield (current, afterDelete)
    }
    current.isDefined              shouldBe true
    current.get.entityInstanceId  shouldBe entityId
    current.get.fields.noSpaces   should include("done")
    afterDelete                    shouldBe None
  }

  test("listCurrent — no filters returns all non-deleted entity states") {
    val (docId, schemaId) = seedPrerequisites()
    val entityId          = UUID.randomUUID()
    val facts = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Create,
                     Json.obj("title" -> Json.fromString("Unfiltered task")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.listCurrent(None, None, None, None, 100, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    facts should not be empty
    facts.map(_.entityInstanceId) should contain(entityId)
  }

  test("listCurrent — filtered by personId returns only that person's facts") {
    val (docId, schemaId, personId, _, _) = seedFull()
    val entityId                           = UUID.randomUUID()
    val facts = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Create,
                     Json.obj("title" -> Json.fromString("Person-filtered task")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.listCurrent(Some(personId), None, None, None, 100, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    facts should not be empty
    facts.map(_.entityInstanceId) should contain(entityId)
  }

  test("listCurrent — filtered by entityType returns only matching facts") {
    val (docId, schemaId, _, _, entityType) = seedFull()
    val entityId                             = UUID.randomUUID()
    val facts = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Create,
                     Json.obj("title" -> Json.fromString("EntityType-filtered task")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        list    <- repo.listCurrent(None, None, None, Some(entityType), 100, 0).provideEnvironment(ZEnvironment(sharedPool))
      yield list
    }
    facts should not be empty
    facts.map(_.entityInstanceId) should contain(entityId)
  }

  test("countCurrent — returns correct total and filtered counts") {
    val (docId, schemaId, personId, domainId, _) = seedFull()
    val entityId                                  = UUID.randomUUID()
    val (total, byPerson, byDomain, byPersonAndDomain) = run {
      for
        repoEnv <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        repo     = repoEnv.get[FactRepository]
        _       <- repo.create(CreateFact(docId, schemaId, entityId, OperationType.Create,
                     Json.obj("title" -> Json.fromString("Counted task")), emptyEmbedding))
                     .provideEnvironment(ZEnvironment(sharedPool))
        total              <- repo.countCurrent(None, None, None, None).provideEnvironment(ZEnvironment(sharedPool))
        byPerson           <- repo.countCurrent(Some(personId), None, None, None).provideEnvironment(ZEnvironment(sharedPool))
        byDomain           <- repo.countCurrent(None, None, Some(domainId), None).provideEnvironment(ZEnvironment(sharedPool))
        byPersonAndDomain  <- repo.countCurrent(Some(personId), None, Some(domainId), None).provideEnvironment(ZEnvironment(sharedPool))
      yield (total, byPerson, byDomain, byPersonAndDomain)
    }
    total              should be >= 1L
    byPerson           should be >= 1L
    byDomain           should be >= 1L
    byPersonAndDomain  should be >= 1L
  }

  test("listCurrent — filtered by householdId returns only that household's facts") {
    run {
      for
        schemaRow <- transaction(
                       sql"SELECT id::text, entity_type FROM entity_type_schema LIMIT 1"
                         .query[(String, String)].selectOne
                     ).mapError(AppError.DatabaseError(_))
                      .flatMap(ZIO.fromOption(_).mapError(_ =>
                        AppError.InternalError(new RuntimeException("No schema found"))))
                      .provideEnvironment(ZEnvironment(sharedPool))
        (schemaIdStr, _) = schemaRow
        schemaId          = UUID.fromString(schemaIdStr)
        sourceTypeIdStr <- transaction(
                             sql"SELECT id::text FROM source_type WHERE name = 'user_input'".query[String].selectOne
                           ).mapError(AppError.DatabaseError(_))
                            .flatMap(ZIO.fromOption(_).mapError(_ =>
                              AppError.InternalError(new RuntimeException("No source type found"))))
                            .provideEnvironment(ZEnvironment(sharedPool))
        sourceTypeId      = UUID.fromString(sourceTypeIdStr)
        householdEnv     <- (ZLayer.succeed(sharedPool) >>> HouseholdRepository.live).build
        docEnv           <- (ZLayer.succeed(sharedPool) >>> DocumentRepository.live).build
        factEnv          <- (ZLayer.succeed(sharedPool) >>> FactRepository.live).build
        household        <- householdEnv.get[HouseholdRepository]
                              .create(CreateHousehold("Fact Household"))
                              .provideEnvironment(ZEnvironment(sharedPool))
        doc              <- docEnv.get[DocumentRepository]
                              .create(CreateDocument(
                                personId      = None,
                                householdId   = Some(household.id),
                                contentText   = "Household fact doc",
                                sourceTypeId  = sourceTypeId,
                                embedding     = emptyEmbedding,
                                files         = Json.arr(),
                                supersedesIds = Nil,
                              ))
                              .provideEnvironment(ZEnvironment(sharedPool))
        entityId          = UUID.randomUUID()
        _                <- factEnv.get[FactRepository]
                              .create(CreateFact(doc.id, schemaId, entityId, OperationType.Create,
                                Json.obj("title" -> Json.fromString("Household task")), emptyEmbedding))
                              .provideEnvironment(ZEnvironment(sharedPool))
        list             <- factEnv.get[FactRepository]
                              .listCurrent(None, Some(household.id), None, None, 100, 0)
                              .provideEnvironment(ZEnvironment(sharedPool))
      yield list.map(_.entityInstanceId) should contain(entityId)
    }
  }
