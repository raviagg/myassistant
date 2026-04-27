package com.myassistant.config

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

/** Root configuration case class wiring all subsystem configs together.
 *
 *  Auto-derived from HOCON via zio-config-magnolia.
 *  The ZLayer provided here reads from `application.conf` on the classpath
 *  and merges any environment variable overrides defined in the HOCON file.
 */
final case class AppConfig(
    server:      ServerConfig,
    database:    DatabaseConfig,
    auth:        AuthConfig,
    fileStorage: FileStorageConfig,
)

object AppConfig:

  /** ZLayer that loads the full AppConfig from application.conf. */
  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      TypesafeConfigProvider
        .fromResourcePath()
        .load(deriveConfig[AppConfig].nested("myassistant"))
    )
