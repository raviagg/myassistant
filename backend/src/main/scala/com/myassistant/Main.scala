package com.myassistant

import com.myassistant.api.Router
import com.myassistant.config.*
import com.myassistant.db.*
import com.myassistant.db.repositories.*
import com.myassistant.logging.AppLogger
import com.myassistant.monitoring.Metrics
import com.myassistant.services.*
import zio.*
import zio.http.*
import zio.jdbc.*

/** ZIO application entry point.
 *
 *  Wires the full layer graph:
 *   AppConfig → DatabaseConfig → ZConnectionPool → Repositories → Services → Router
 *
 *  Startup sequence:
 *   1. Load configuration from application.conf
 *   2. Initialise Prometheus hotspot metrics
 *   3. Run Flyway migrations
 *   4. Start ZIO HTTP server
 */
object Main extends ZIOAppDefault:

  /** Override the ZIO runtime logger with the structured SLF4J logger. */
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    AppLogger.live

  /** Full layer stack providing everything the HTTP app needs. */
  private val appLayer: ZLayer[Any, Throwable, Router.AppEnv] =
    // ── Config ────────────────────────────────────────────────
    val configLayer       = AppConfig.live
    val serverConfigLayer = configLayer >>> ZLayer.fromFunction((_: AppConfig).server)
    val dbConfigLayer     = configLayer >>> ZLayer.fromFunction((_: AppConfig).database)
    val authConfigLayer   = configLayer >>> ZLayer.fromFunction((_: AppConfig).auth)
    val fileConfigLayer   = configLayer >>> ZLayer.fromFunction((_: AppConfig).fileStorage)

    // ── Database ──────────────────────────────────────────────
    val poolLayer = dbConfigLayer >>> DatabaseModule.connectionPoolLive

    // ── Repositories ──────────────────────────────────────────
    val personRepoLayer       = PersonRepository.live
    val householdRepoLayer    = HouseholdRepository.live
    val relationshipRepoLayer = RelationshipRepository.live
    val documentRepoLayer     = DocumentRepository.live
    val factRepoLayer         = FactRepository.live
    val schemaRepoLayer       = SchemaRepository.live
    val referenceRepoLayer    = ReferenceRepository.live
    val auditRepoLayer        = AuditRepository.live
    val fileRepoLayer         = FileRepository.live

    // ── Services ──────────────────────────────────────────────
    val personSvcLayer       = personRepoLayer       >>> PersonService.live
    val householdSvcLayer    = householdRepoLayer    >>> HouseholdService.live
    val relationshipSvcLayer = relationshipRepoLayer >>> RelationshipService.live
    val kinshipSvcLayer      = (relationshipRepoLayer ++ referenceRepoLayer) >>> KinshipResolver.live
    val documentSvcLayer     = documentRepoLayer     >>> DocumentService.live
    val factSvcLayer         = factRepoLayer         >>> FactService.live
    val schemaSvcLayer       = schemaRepoLayer       >>> SchemaService.live
    val referenceSvcLayer    = referenceRepoLayer    >>> ReferenceService.live
    val auditSvcLayer        = auditRepoLayer        >>> AuditService.live
    val fileSvcLayer         = fileConfigLayer       >>> FileService.live

    poolLayer ++
      personSvcLayer ++
      householdSvcLayer ++
      relationshipSvcLayer ++
      kinshipSvcLayer ++
      documentSvcLayer ++
      factSvcLayer ++
      schemaSvcLayer ++
      referenceSvcLayer ++
      auditSvcLayer ++
      fileSvcLayer ++
      authConfigLayer

  /** Application entry point — start the HTTP server. */
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for
      _   <- ZIO.logInfo("Starting myassistant backend...")
      _   <- ZIO.succeed(Metrics.initHotspot())
      _   <- MigrationRunner.migrate.provide(
               AppConfig.live >>> ZLayer.fromFunction((_: AppConfig).database)
             )
      cfg <- ZIO.service[AppConfig].provide(AppConfig.live)
      app <- Router.app.provide(appLayer)
      _   <- ZIO.logInfo(s"Server starting on 0.0.0.0:${cfg.server.port}")
      _   <- Server
               .serve(app)
               .provide(
                 Server.defaultWithPort(cfg.server.port),
                 appLayer,
               )
    yield ()
