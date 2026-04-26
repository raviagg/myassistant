package com.myassistant.unit

import com.myassistant.db.repositories.FileRepository
import com.myassistant.errors.AppError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import java.util.UUID
import java.nio.file.{Files, Path => NioPath}

class FileRepositorySpec extends AnyFunSuite with Matchers:

  private val repo = new FileRepository.Live

  private def run[A](effect: ZIO[Any, AppError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("register with personId only — succeeds and returns the path") {
    val personId = UUID.randomUUID()
    val result   = run(repo.register(Some(personId), None, "/tmp/test-file.pdf", "application/pdf"))
    result shouldBe "/tmp/test-file.pdf"
  }

  test("register with householdId only — succeeds and returns the path") {
    val householdId = UUID.randomUUID()
    val result      = run(repo.register(None, Some(householdId), "/tmp/household-file.jpg", "image/jpeg"))
    result shouldBe "/tmp/household-file.jpg"
  }

  test("register with neither personId nor householdId — fails with ValidationError") {
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        repo.register(None, None, "/tmp/orphan.txt", "text/plain").exit
      ).getOrThrow()
    }
    result.isFailure shouldBe true
    val appError: Option[AppError] = result match
      case zio.Exit.Failure(cause) => cause.failureOption
      case _                       => None
    appError.isDefined shouldBe true
    appError.get       shouldBe a [AppError.ValidationError]
  }

  test("exists for a real tmp file — returns true") {
    val tmpFile = Files.createTempFile("file-repo-test", ".tmp")
    try
      val result = run(repo.exists(tmpFile.toString))
      result shouldBe true
    finally
      Files.deleteIfExists(tmpFile)
  }

  test("exists for nonexistent path — returns false") {
    val result = run(repo.exists("/tmp/this-file-does-not-exist-" + UUID.randomUUID().toString))
    result shouldBe false
  }

  test("live ZLayer provides a functional FileRepository") {
    val personId = UUID.randomUUID()
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        ZIO.serviceWithZIO[FileRepository](
          _.register(Some(personId), None, "/tmp/layer-test.pdf", "application/pdf")
        ).provide(FileRepository.live)
      ).getOrThrowFiberFailure()
    }
    result shouldBe "/tmp/layer-test.pdf"
  }
