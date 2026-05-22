package com.myassistant.config

import java.util.Base64

/** Configuration for the AES-256-GCM secrets encryption used by
 *  source_connections.secrets.
 *
 *  The key is supplied via the SECRETS_KEY environment variable
 *  and must be a 32-byte (256-bit) value Base64-encoded.
 *  Generate with: openssl rand -base64 32
 */
final case class SecretsConfig(secretsKey: String):

  /** Validate the key is valid Base64 that decodes to exactly 32 bytes.
   *  Called at startup via Main.validateSecrets before serving requests.
   */
  def validate(): Either[String, Unit] =
    try
      val keyBytes = Base64.getDecoder.decode(secretsKey)
      if keyBytes.length != 32 then
        Left(s"SECRETS_KEY must decode to exactly 32 bytes (got ${keyBytes.length}). Generate with: openssl rand -base64 32")
      else
        Right(())
    catch
      case _: IllegalArgumentException =>
        Left("SECRETS_KEY is not valid Base64. Generate with: openssl rand -base64 32")
