package com.myassistant.e2e

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.myassistant.api.routes.{AuditRoutes, DocumentRoutes, FactRoutes, HouseholdRoutes, PersonHouseholdRoutes, PersonRoutes, ReferenceRoutes, RelationshipRoutes, SchemaRoutes}
import com.myassistant.config.DatabaseConfig
import com.myassistant.db.DatabaseModule
import com.myassistant.db.repositories.{AuditRepository, DocumentRepository, FactRepository, HouseholdRepository, PersonRepository, ReferenceRepository, RelationshipRepository, SchemaRepository}
import com.myassistant.services.{AuditService, DocumentService, FactService, HouseholdService, KinshipResolver, PersonService, ReferenceService, RelationshipService, SchemaService}
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.http.*
import zio.jdbc.ZConnectionPool

object CucumberServer:

  val TestPort = 8181

  private val routes =
    Routes(
      Method.GET / "health" -> handler(Response.json("""{"status":"ok","version":"0.1.0"}""")),
    ) ++ PersonRoutes.routes ++ SchemaRoutes.routes ++ DocumentRoutes.routes ++ FactRoutes.routes ++
      HouseholdRoutes.routes ++ PersonHouseholdRoutes.routes ++
      RelationshipRoutes.routes ++ ReferenceRoutes.routes ++ AuditRoutes.routes

  // Start PostgreSQL container synchronously at object initialisation.
  // Blocked until the container reports healthy; subsequent JVM runs reuse
  // the cached Docker image so startup is fast (~2-3 s).
  private val container: PostgreSQLContainer =
    val c = PostgreSQLContainer.Def(
      dockerImageName = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"),
      databaseName    = "myassistant_e2e",
      username        = "test",
      password        = "test",
    ).start()
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(() => c.stop()))
    c

  private val dbConfig: DatabaseConfig = DatabaseConfig(
    url               = container.jdbcUrl.split("\\?").head,
    user              = container.username,
    password          = container.password,
    poolSize          = 2,
    connectionTimeout = 5000,
    idleTimeout       = 30000,
    maxLifetime       = 60000,
  )

  private val dbLayer: ZLayer[Any, Throwable, ZConnectionPool] =
    ZLayer.succeed(dbConfig) >>> DatabaseModule.connectionPoolLive

  private def startEmbeddedServer(): Unit =
    // Run Flyway directly (not via ZIO runtime) to avoid thread-pool blocking.
    // Strip `?loggerLevel=OFF` — that parameter was removed in PostgreSQL JDBC 42.x
    // and causes a "Property not supported" error when Flyway tries to open the DataSource.
    val cleanJdbcUrl = container.jdbcUrl.split("\\?").head
    org.flywaydb.core.Flyway
      .configure()
      .dataSource(cleanJdbcUrl, container.username, container.password)
      .locations("classpath:db/migration")
      .baselineOnMigrate(true)
      .load()
      .migrate()
    java.lang.System.out.println("[CucumberServer] Flyway migrations applied")

    val runnable: Runnable = () =>
      try
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(
            Server
              .serve(routes)
              .provide(
                Server.defaultWithPort(TestPort),
                dbLayer,
                PersonRepository.live,
                PersonService.live,
                SchemaRepository.live,
                SchemaService.live,
                DocumentRepository.live,
                DocumentService.live,
                FactRepository.live,
                FactService.live,
                HouseholdRepository.live,
                HouseholdService.live,
                RelationshipRepository.live,
                RelationshipService.live,
                ReferenceRepository.live,
                ReferenceService.live,
                KinshipResolver.live,
                AuditRepository.live,
                AuditService.live,
              )
          ).getOrThrowFiberFailure()
        }
      catch
        case t: Throwable =>
          java.lang.System.err.println(s"[CucumberServer] Server thread crashed: ${t.getClass.getName}: ${t.getMessage}")
          t.printStackTrace(java.lang.System.err)

    val t = new Thread(runnable, "cucumber-test-server")
    t.setDaemon(true)
    t.start()

  if sys.env.get("TEST_BASE_URL").isEmpty && sys.props.get("TEST_BASE_URL").isEmpty then
    java.lang.System.setProperty("TEST_BASE_URL", s"http://localhost:$TestPort")
    startEmbeddedServer()
