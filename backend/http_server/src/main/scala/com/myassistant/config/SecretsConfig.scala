package com.myassistant.config

/** Configuration for the AES-256-GCM secrets encryption used by
 *  source_connections.secrets.
 *
 *  The key is supplied via the SECRETS_KEY environment variable
 *  and must be a 32-byte (256-bit) value Base64-encoded.
 *  Generate with: openssl rand -base64 32
 */
final case class SecretsConfig(secretsKey: String)
