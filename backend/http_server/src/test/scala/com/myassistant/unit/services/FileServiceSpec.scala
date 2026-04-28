package com.myassistant.unit.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import com.myassistant.services.FileService
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.Files
import java.util.UUID

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

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("FileServiceSpec")(

      withTempDir(
        suite("upload")(

          test("fails with ValidationError when neither personId nor householdId is set") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.upload(None, None, "test.txt", "text/plain", "hello".getBytes).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("writes file and returns storage key for person owner") {
            val personId = UUID.randomUUID()
            for
              svc    <- ZIO.service[FileService]
              key    <- svc.upload(Some(personId), None, "data.txt", "text/plain", "content".getBytes)
              fileOk <- svc.exists(key)
            yield assertTrue(key.endsWith("data.txt")) &&
                  assertTrue(fileOk)
          },

          test("writes file and returns storage key for household owner") {
            val householdId = UUID.randomUUID()
            for
              svc <- ZIO.service[FileService]
              key <- svc.upload(None, Some(householdId), "report.pdf", "application/pdf", Array[Byte](1, 2, 3))
            yield assertTrue(key.endsWith("report.pdf"))
          },

          test("creates the base directory when it does not yet exist") {
            for
              parent <- ZIO.attempt(java.nio.file.Files.createTempDirectory("fs-parent")).orDie
              subDir  = parent.resolve("auto-created").toString
              svc     = new FileService.Live(FileStorageConfig(subDir))
              key    <- svc.upload(Some(UUID.randomUUID()), None, "new.txt", "text/plain", "hello".getBytes)
              ok     <- svc.exists(key)
            yield assertTrue(ok)
          },

        )
      ),

      withTempDir(
        suite("download")(

          test("fails with NotFound for a key that does not exist") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.download("/tmp/nonexistent_file_xyz_12345").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the bytes written by upload") {
            val personId = UUID.randomUUID()
            val content  = "round-trip content".getBytes
            for
              svc   <- ZIO.service[FileService]
              key   <- svc.upload(Some(personId), None, "round.txt", "text/plain", content)
              bytes <- svc.download(key)
            yield assertTrue(bytes sameElements content)
          },

        )
      ),

      withTempDir(
        suite("exists")(

          test("returns false for a missing key") {
            for
              svc    <- ZIO.service[FileService]
              result <- svc.exists("/tmp/certainly_does_not_exist_abc")
            yield assertTrue(!result)
          },

          test("returns true after a successful upload") {
            val personId = UUID.randomUUID()
            for
              svc    <- ZIO.service[FileService]
              key    <- svc.upload(Some(personId), None, "exists.txt", "text/plain", "x".getBytes)
              result <- svc.exists(key)
            yield assertTrue(result)
          },

        )
      ),

    )
