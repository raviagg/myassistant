package com.myassistant.config

/** PostgreSQL / HikariCP connection pool configuration.
 *
 *  Populated from the `myassistant.database` HOCON block.
 */
final case class DatabaseConfig(
    /** JDBC connection URL, e.g. jdbc:postgresql://host:5432/dbname. */
    url: String,
    /** Database user name. */
    user: String,
    /** Database password. */
    password: String,
    /** Maximum number of connections in the HikariCP pool. */
    poolSize: Int,
    /** Connection acquisition timeout in milliseconds. */
    connectionTimeout: Long,
    /** Idle connection eviction timeout in milliseconds. */
    idleTimeout: Long,
    /** Maximum lifetime of a pooled connection in milliseconds. */
    maxLifetime: Long,
)
