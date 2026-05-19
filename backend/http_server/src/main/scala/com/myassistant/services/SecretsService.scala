package com.myassistant.services

import com.myassistant.config.SecretsConfig

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Try

/** AES-256-GCM encrypt/decrypt for `source_connections.secrets`.
 *
 *  The output format is a single Base64-encoded string consisting of
 *  the 12-byte random IV followed by the GCM ciphertext (which
 *  includes the 128-bit authentication tag). Decryption splits the
 *  IV off the front and verifies the tag.
 *
 *  The key is supplied via SecretsConfig.secretsKey as a Base64-encoded
 *  32-byte (256-bit) value.
 */
object SecretsService:

  private val AES_GCM_ALGO    = "AES/GCM/NoPadding"
  private val TAG_BIT_LENGTH  = 128
  private val IV_BYTE_LENGTH  = 12

  /** Encrypt a UTF-8 plaintext string with AES-256-GCM.
   *
   *  Returns a Base64-encoded ciphertext (IV ‖ encrypted ‖ tag) or a
   *  Throwable describing the failure (bad key, JCE unavailable, etc.).
   */
  def encrypt(plaintext: String, config: SecretsConfig): Either[Throwable, String] =
    Try {
      val keyBytes = Base64.getDecoder.decode(config.secretsKey)
      val keySpec  = new SecretKeySpec(keyBytes, "AES")
      val iv       = new Array[Byte](IV_BYTE_LENGTH)
      new java.security.SecureRandom().nextBytes(iv)
      val cipher   = Cipher.getInstance(AES_GCM_ALGO)
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BIT_LENGTH, iv))
      val encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"))
      // Prepend IV to ciphertext and Base64-encode the whole thing
      val combined = iv ++ encrypted
      Base64.getEncoder.encodeToString(combined)
    }.toEither

  /** Decrypt a Base64-encoded ciphertext previously produced by `encrypt`.
   *
   *  Returns the plaintext UTF-8 string or a Throwable describing the
   *  failure (bad key, malformed input, tag mismatch, etc.).
   */
  def decrypt(ciphertext: String, config: SecretsConfig): Either[Throwable, String] =
    Try {
      val keyBytes  = Base64.getDecoder.decode(config.secretsKey)
      val keySpec   = new SecretKeySpec(keyBytes, "AES")
      val combined  = Base64.getDecoder.decode(ciphertext)
      val iv        = combined.slice(0, IV_BYTE_LENGTH)
      val encrypted = combined.slice(IV_BYTE_LENGTH, combined.length)
      val cipher    = Cipher.getInstance(AES_GCM_ALGO)
      cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BIT_LENGTH, iv))
      new String(cipher.doFinal(encrypted), "UTF-8")
    }.toEither
