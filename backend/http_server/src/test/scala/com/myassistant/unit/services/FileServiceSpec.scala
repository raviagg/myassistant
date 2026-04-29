package com.myassistant.unit.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import com.myassistant.services.FileService
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.Files
import java.util.Base64

object FileServiceSpec extends ZIOSpecDefault:

  // ── Layer factory using a real temp directory ──────────────────────────────

  private def withTempDir[E](spec: Spec[FileService, E]): Spec[Any, E] =
    spec.provideSome[Any](
      ZLayer.fromZIO(
        ZIO.attempt(Files.createTempDirectory("file-service-test").toString)
          .orDie
          .map(FileStorageConfig(_))
      ),
      FileService.live,
    )

  private def encode(s: String): String = Base64.getEncoder.encodeToString(s.getBytes("UTF-8"))

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("FileServiceSpec")(

      withTempDir(
        suite("upload")(

          test("returns a filePath ending with the sanitised filename") {
            for
              svc            <- ZIO.service[FileService]
              (filePath, sz) <- svc.upload("data.txt", "text/plain", encode("content"))
            yield assertTrue(filePath.contains("data.txt")) &&
                  assertTrue(sz == 7L)
          },

          test("returns correct sizeBytes for the decoded content") {
            val content = "hello world"
            for
              svc            <- ZIO.service[FileService]
              (filePath, sz) <- svc.upload("hello.txt", "text/plain", encode(content))
            yield assertTrue(sz == content.length.toLong)
          },

          test("fails with ValidationError for invalid base64 content") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.upload("test.txt", "text/plain", "not!!valid==base64###").exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

        )
      ),

      withTempDir(
        suite("download")(

          test("fails with NotFound for a path that does not exist") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.download("/tmp/nonexistent_file_xyz_12345_abc").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the bytes written by upload") {
            val content = "round-trip content"
            for
              svc              <- ZIO.service[FileService]
              (filePath, _)    <- svc.upload("round.txt", "text/plain", encode(content))
              (bytes, mime, fn) <- svc.download(filePath)
            yield assertTrue(bytes.length == content.getBytes("UTF-8").length) &&
                  assertTrue(fn.contains("round.txt"))
          },

        )
      ),

      withTempDir(
        suite("delete")(

          test("fails with NotFound when file does not exist") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.delete("/tmp/certainly_does_not_exist_abc_xyz").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("deletes a previously uploaded file") {
            for
              svc           <- ZIO.service[FileService]
              (filePath, _) <- svc.upload("del.txt", "text/plain", encode("bye"))
              _             <- svc.delete(filePath)
              result        <- svc.download(filePath).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

      withTempDir(
        suite("extractText")(

          test("fails with NotFound for a path that does not exist") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.extractText("/tmp/no_such_file_xyz_abc").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns text content and extraction method for a .txt file") {
            val content = "Hello, world!"
            for
              svc              <- ZIO.service[FileService]
              (filePath, _)    <- svc.upload("note.txt", "text/plain", encode(content))
              (text, method)   <- svc.extractText(filePath)
            yield assertTrue(text.contains("Hello")) &&
                  assertTrue(method == "plain_text")
          },

        )
      ),

    )
