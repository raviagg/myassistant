package com.myassistant.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
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

  private val textExtensions  = Set(".txt", ".md", ".csv", ".html", ".xml", ".json", ".yaml", ".yml", ".log")
  private val imageExtensions = Set(".jpg", ".jpeg", ".png", ".tiff", ".tif", ".bmp", ".gif", ".webp")

  private def fileExtension(filename: String): String =
    val lower = filename.toLowerCase
    val i     = lower.lastIndexOf('.')
    if i == -1 then "" else lower.substring(i)

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
        path
      }.mapError {
        case _: java.io.FileNotFoundException => AppError.NotFound("file", filePath)
        case e                                 => AppError.FileSystemError(e)
      }.flatMap { path =>
        val ext = fileExtension(path.getFileName.toString)
        if ext == ".pdf" then
          extractFromPdf(path.toString)
        else if textExtensions.contains(ext) then
          extractPlainText(path.toString)
        else if imageExtensions.contains(ext) then
          extractViaOcr(path.toString)
        else
          ZIO.fail(AppError.ValidationError(
            s"Unsupported file type '$ext' for text extraction. Supported: pdf, images (jpg/jpeg/png/tiff/bmp/gif/webp), text (txt/md/csv/html/xml/json/yaml/yml/log)"
          ))
      }

    private def extractFromPdf(filePath: String): ZIO[Any, AppError, (String, String)] =
      ZIO.attempt {
        val doc = Loader.loadPDF(new java.io.File(filePath))
        try
          val text = new PDFTextStripper().getText(doc).trim
          (text, "pdf_parser")
        finally
          doc.close()
      }.mapError(e => AppError.FileSystemError(e))

    private def extractPlainText(filePath: String): ZIO[Any, AppError, (String, String)] =
      ZIO.attempt {
        val bytes = Files.readAllBytes(Paths.get(filePath))
        val text  = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
          .replaceAll("[^\\x09\\x0A\\x0D\\x20-\\x7E\\u00A0-\\uD7FF\\uE000-\\uFFFD]", " ")
          .trim
        (text, "plain_text")
      }.mapError(e => AppError.FileSystemError(e))

    private def extractViaOcr(filePath: String): ZIO[Any, AppError, (String, String)] =
      ZIO.attempt {
        val instance = new Tesseract()
        findTessdata().foreach(instance.setDatapath)
        instance.setLanguage("eng")
        val text = instance.doOCR(new java.io.File(filePath))
        (text.trim, "ocr")
      }.mapError(e => AppError.FileSystemError(e))

    private def findTessdata(): Option[String] =
      List(
        sys.env.get("TESSDATA_PREFIX"),
        Some("/opt/homebrew/share/tessdata"),   // macOS Apple Silicon (Homebrew)
        Some("/usr/local/share/tessdata"),      // macOS Intel / Linux
        Some("/usr/share/tessdata"),            // Linux system package
      ).flatten.find(p => java.io.File(p).isDirectory)

  val live: ZLayer[FileStorageConfig, Nothing, FileService] =
    ZLayer.fromFunction(new Live(_))
