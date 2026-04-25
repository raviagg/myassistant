package com.myassistant.db.repositories

import com.myassistant.errors.AppError
import zio.*

import java.nio.file.{Files, Paths}
import java.util.UUID

/** Data-access interface for persisted file metadata (not file content). */
trait FileRepository:
  /** Register a file path entry and return its storage key. */
  def register(personId: Option[UUID], householdId: Option[UUID], path: String, mimeType: String): ZIO[Any, AppError, String]

  /** Check whether a file with the given storage key exists. */
  def exists(key: String): ZIO[Any, AppError, Boolean]

object FileRepository:

  /** Live implementation — file metadata tracking backed by the local filesystem.
   *
   *  This repository does not hold a database connection.  Actual file content is
   *  written and read by FileService; this class only validates ownership and
   *  checks physical existence on disk.
   */
  final class Live extends FileRepository:

    /** Validate ownership and return the storage key (the path itself).
     *
     *  The file path is treated as the storage key — no separate DB record is
     *  created.  Actual file writes are performed by FileService before calling
     *  this method.
     */
    def register(
        personId:    Option[UUID],
        householdId: Option[UUID],
        path:        String,
        mimeType:    String,
    ): ZIO[Any, AppError, String] =
      if personId.isEmpty && householdId.isEmpty then
        ZIO.fail(AppError.ValidationError("A file must be associated with a person or household"))
      else
        ZIO.succeed(path)

    /** Check whether the file at the given key/path exists on disk. */
    def exists(key: String): ZIO[Any, AppError, Boolean] =
      ZIO.attempt(Files.exists(Paths.get(key)))
        .mapError(e => AppError.FileSystemError(e))

  /** ZLayer providing the live FileRepository. */
  val live: ZLayer[Any, Nothing, FileRepository] =
    ZLayer.succeed(new Live)
