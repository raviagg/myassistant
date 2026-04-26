package com.myassistant.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import zio.*

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.UUID

/** Business-logic layer for file upload and retrieval.
 *
 *  Abstracts over the underlying storage backend (local filesystem or S3).
 *  File metadata is tracked by FileRepository; content is stored at basePath.
 */
trait FileService:
  /** Store file bytes at the configured basePath and return the storage key. */
  def upload(
      personId:    Option[UUID],
      householdId: Option[UUID],
      fileName:    String,
      mimeType:    String,
      content:     Array[Byte],
  ): ZIO[Any, AppError, String]

  /** Retrieve file bytes by storage key. */
  def download(key: String): ZIO[Any, AppError, Array[Byte]]

  /** Check whether a file exists at the given storage key. */
  def exists(key: String): ZIO[Any, AppError, Boolean]

object FileService:

  /** Live implementation writing to the local filesystem under `cfg.basePath`. */
  final class Live(cfg: FileStorageConfig) extends FileService:

    /** Write bytes to `basePath/<uuid>_<fileName>` and return the storage key.
     *
     *  Fails with ValidationError if neither personId nor householdId is set.
     *  Fails with FileSystemError on any I/O exception.
     */
    def upload(
        personId:    Option[UUID],
        householdId: Option[UUID],
        fileName:    String,
        mimeType:    String,
        content:     Array[Byte],
    ): ZIO[Any, AppError, String] =
      if personId.isEmpty && householdId.isEmpty then
        ZIO.fail(AppError.ValidationError("A file must be associated with a person or household"))
      else
        ZIO.attempt {
          val base      = Paths.get(cfg.basePath)
          if !Files.exists(base) then Files.createDirectories(base)
          val key       = s"${UUID.randomUUID()}_$fileName"
          val targetPath = base.resolve(key)
          Files.write(targetPath, content, StandardOpenOption.CREATE_NEW)
          targetPath.toString
        }.mapError(e => AppError.FileSystemError(e))

    /** Read and return all bytes from the file at the given storage key (absolute path).
     *
     *  Fails with NotFound if the file does not exist on disk.
     *  Fails with FileSystemError on any other I/O exception.
     */
    def download(key: String): ZIO[Any, AppError, Array[Byte]] =
      ZIO.attempt {
        val path = Paths.get(key)
        if !Files.exists(path) then
          throw new java.io.FileNotFoundException(s"File not found at key: $key")
        Files.readAllBytes(path)
      }.mapError:
        case _: java.io.FileNotFoundException => AppError.NotFound("file", key)
        case e                                => AppError.FileSystemError(e)

    /** Check whether a file exists at the given storage key. */
    def exists(key: String): ZIO[Any, AppError, Boolean] =
      ZIO.attempt(Files.exists(Paths.get(key)))
        .mapError(e => AppError.FileSystemError(e))

  /** ZLayer providing the live FileService. */
  val live: ZLayer[FileStorageConfig, Nothing, FileService] =
    ZLayer.fromFunction(new Live(_))
