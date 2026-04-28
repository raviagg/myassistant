package com.myassistant.db

import com.myassistant.config.DatabaseConfig
import org.flywaydb.core.Flyway
import zio.*

/** Runs Flyway database migrations on application startup.
 *
 *  Migration SQL files live in `src/main/resources/db/migration/` and
 *  are named V1__, V2__, … in the standard Flyway convention.
 *  Migrations are idempotent — running them again on an already-migrated
 *  database is safe.
 */
object MigrationRunner:

  /** Execute all pending Flyway migrations against the configured database. */
  val migrate: ZIO[DatabaseConfig, Throwable, Unit] =
    for
      cfg <- ZIO.service[DatabaseConfig]
      _   <- ZIO.attempt {
               Flyway
                 .configure()
                 .dataSource(cfg.url, cfg.user, cfg.password)
                 .locations("classpath:db/migration")
                 .baselineOnMigrate(true)
                 .load()
                 .migrate()
             }
      _   <- ZIO.logInfo("Flyway migrations applied successfully")
    yield ()

  /** ZLayer that runs migrations as a startup effect. */
  val live: ZLayer[DatabaseConfig, Throwable, Unit] =
    ZLayer.fromZIO(migrate)
