package com.myassistant.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import zio.*

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDate
import java.util.{Base64, UUID}

trait FileService:
  def upload(filename: String, mimeType: String, contentBase64: String): ZIO[Any, AppError, (String, Long)]
  def download(filePath: String): ZIO[Any, AppError, (Array[Byte], String, String)]
  def delete(filePath: String): ZIO[Any, AppError, Unit]
  def extractText(filePath: String): ZIO[Any, AppError, (String, String)]

object FileService:

  final class Live(cfg: FileStorageConfig) extends FileService:

    def upload(filename: String, mimeType: String, contentBase64: String): ZIO[Any, AppError, (String, Long)] =
      ZIO.attempt {
        val bytes = Base64.getDecoder.decode(contentBase64)
        val today = LocalDate.now()
        val dir   = Paths.get(cfg.basePath, today.getYear.toString, f"${today.getMonthValue}%02d", f"${today.getDayOfMonth}%02d")
        Files.createDirectories(dir)
        val safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_")
        val unique   = s"${UUID.randomUUID().toString.take(8)}-$safeName"
        val target   = dir.resolve(unique)
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
        (target.toString, bytes.length.toLong)
      }.mapError {
        case e: IllegalArgumentException => AppError.ValidationError(s"Invalid base64 content: ${e.getMessage}")
        case e                            => AppError.FileSystemError(e)
      }

    def download(filePath: String): ZIO[Any, AppError, (Array[Byte], String, String)] =
      ZIO.attempt {
        val path     = Paths.get(filePath)
        if !Files.exists(path) then
          throw new java.io.FileNotFoundException(s"File not found: $filePath")
        val bytes    = Files.readAllBytes(path)
        val filename = path.getFileName.toString
        (bytes, "application/octet-stream", filename)
      }.mapError:
        case _: java.io.FileNotFoundException => AppError.NotFound("file", filePath)
        case e                                 => AppError.FileSystemError(e)

    def delete(filePath: String): ZIO[Any, AppError, Unit] =
      ZIO.attempt {
        val path = Paths.get(filePath)
        if !Files.deleteIfExists(path) then
          throw new java.io.FileNotFoundException(s"File not found: $filePath")
      }.mapError:
        case _: java.io.FileNotFoundException => AppError.NotFound("file", filePath)
        case e                                 => AppError.FileSystemError(e)

    def extractText(filePath: String): ZIO[Any, AppError, (String, String)] =
      ZIO.attempt {
        val path = Paths.get(filePath)
        if !Files.exists(path) then
          throw new java.io.FileNotFoundException(s"File not found: $filePath")
        val bytes    = Files.readAllBytes(path)
        val filename = path.getFileName.toString.toLowerCase
        val method   =
          if filename.endsWith(".pdf") then "pdf_parser"
          else if filename.endsWith(".txt") || filename.endsWith(".md") then "plain_text"
          else "plain_text"
        val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
          .replaceAll("[^\\x09\\x0A\\x0D\\x20-\\x7E\\u00A0-\\uD7FF\\uE000-\\uFFFD]", " ")
          .trim
        (text, method)
      }.mapError:
        case _: java.io.FileNotFoundException => AppError.NotFound("file", filePath)
        case e                                 => AppError.FileSystemError(e)

  val live: ZLayer[FileStorageConfig, Nothing, FileService] =
    ZLayer.fromFunction(new Live(_))
