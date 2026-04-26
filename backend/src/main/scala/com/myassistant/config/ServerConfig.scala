package com.myassistant.config

/** HTTP server binding configuration.
 *
 *  Populated from the `myassistant.server` HOCON block.
 */
final case class ServerConfig(
    /** Bind address, e.g. "0.0.0.0" or "127.0.0.1". */
    host: String,
    /** TCP port to listen on, e.g. 8080. */
    port: Int,
)
