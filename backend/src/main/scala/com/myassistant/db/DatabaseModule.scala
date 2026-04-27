package com.myassistant.db

import com.myassistant.config.DatabaseConfig
import zio.*
import zio.jdbc.*

import java.net.URI

/** Provides the ZIO JDBC connection pool backed by zio-jdbc's built-in pool management.
 *
 *  Parses the JDBC URL from DatabaseConfig into its host/port/database components
 *  and delegates connection lifecycle to zio-jdbc's `ZConnectionPool.postgres` factory.
 */
object DatabaseModule:

  /** ZLayer providing a ZConnectionPool configured from DatabaseConfig.
   *
   *  Parses `jdbc:postgresql://host:port/database` from the configured URL.
   */
  val connectionPoolLive: ZLayer[DatabaseConfig, Throwable, ZConnectionPool] =
    ZLayer.fromZIO(ZIO.service[DatabaseConfig]).flatMap { env =>
      val cfg = env.get[DatabaseConfig]
      // Parse jdbc:postgresql://host:port/database  →  host, port, database
      val uri      = new URI(cfg.url.stripPrefix("jdbc:"))
      val host     = uri.getHost
      val port     = if uri.getPort > 0 then uri.getPort else 5432
      val database = uri.getPath.stripPrefix("/")
      val props    = Map(
        "user"           -> cfg.user,
        "password"       -> cfg.password,
        "connectTimeout" -> (cfg.connectionTimeout / 1000).toString,
        "socketTimeout"  -> "30",
      )
      // minConnections must be <= maxConnections; default.minConnections is 8 which would
      // exceed poolSize=2 in tests, causing ZPool to deadlock during eager initialisation.
      ZLayer.succeed(ZConnectionPoolConfig.default.copy(minConnections = 1, maxConnections = cfg.poolSize)) >>>
        ZConnectionPool.postgres(host, port, database, props)
    }
