package com.myassistant.integration

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.{DatabaseModule, MigrationRunner}
import com.myassistant.db.repositories.UnifiedSchemaRepository
import com.myassistant.domain.{CreateUnifiedSchema, PatchUnifiedSchema}
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

class UnifiedSchemaRepositorySpec extends AnyFunSuite with Matchers with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(
      dockerImageName = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"),
      databaseName    = "myassistant_test",
      username        = "test",
      password        = "test",
    )

  private var sharedPool: ZConnectionPool = uninitialized
  private var poolScope: Scope.Closeable  = uninitialized
  private var testPersonId: UUID          = uninitialized

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
          // Insert a test person
          personIdStr <- transaction(
            sql"""INSERT INTO person(id, display_name, full_name)
                  VALUES (gen_random_uuid()::text::uuid, 'Test Person', 'Test Person')
                  RETURNING id::text""".query[String].selectOne
          ).mapError(AppError.DatabaseError(_))
           .flatMap(ZIO.fromOption(_).mapError(_ => AppError.InternalError(new RuntimeException("no person"))))
           .provideEnvironment(ZEnvironment(pool))
          _            = testPersonId = UUID.fromString(personIdStr)
        yield ()
      ).getOrThrowFiberFailure()
    }

  override def beforeContainersStop(container: PostgreSQLContainer): Unit =
    if poolScope != null then
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(poolScope.close(Exit.succeed(()))).getOrThrowFiberFailure()
      }

  private def run[A](effect: ZIO[ZConnectionPool, AppError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          effect
            .provideEnvironment(ZEnvironment(sharedPool))
            .timeoutFail(new RuntimeException("Test timed out after 30s"))(30.seconds)
        )
        .getOrThrowFiberFailure()
    }

  private val repo = new UnifiedSchemaRepository.Live

  private def makeCreate(name: String = "transaction"): CreateUnifiedSchema =
    CreateUnifiedSchema(
      personId         = Some(testPersonId),
      householdId      = None,
      name             = name,
      description      = Some("test schema"),
      status           = "proposed",
      fieldDefinitions = Json.arr(),
    )

  test("create and findById round-trip") {
    val created = run(repo.create(makeCreate()))
    created.name    shouldBe "transaction"
    created.status  shouldBe "proposed"
    created.personId shouldBe Some(testPersonId)

    val found = run(repo.findById(created.id))
    found.isDefined shouldBe true
    found.get.id    shouldBe created.id
  }

  test("list returns schemas for the given person") {
    val c1 = run(repo.create(makeCreate("txn-list-1")))
    val c2 = run(repo.create(makeCreate("txn-list-2")))
    val all = run(repo.list(Some(testPersonId), None))
    all.map(_.id) should contain allOf (c1.id, c2.id)
  }

  test("patch updates status") {
    val created = run(repo.create(makeCreate("patch-test")))
    val patched = run(repo.patch(created.id,
      PatchUnifiedSchema(None, None, Some("approved"), None)))
    patched.isDefined  shouldBe true
    patched.get.status shouldBe "approved"
  }

  test("delete removes the row") {
    val created = run(repo.create(makeCreate("delete-test")))
    val deleted = run(repo.delete(created.id))
    deleted shouldBe true
    val found = run(repo.findById(created.id))
    found shouldBe None
  }

  test("sourceSchemas returns profile group") {
    val result = run(repo.sourceSchemas(Some(testPersonId), None))
    result.profile.sourceType shouldBe "profile"
    result.profile.tables.map(_.tableName) should contain ("person")
  }
