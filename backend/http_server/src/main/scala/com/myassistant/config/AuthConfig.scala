package com.myassistant.config

/** Authentication configuration.
 *
 *  Populated from the `myassistant.auth` HOCON block.
 *  Currently holds a static bearer token; extend with JWT settings as needed.
 */
final case class AuthConfig(
    /** Static bearer token expected in the Authorization header. */
    token: String,
)
