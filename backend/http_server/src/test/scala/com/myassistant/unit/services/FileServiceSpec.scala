package com.myassistant.unit.services

import com.myassistant.config.FileStorageConfig
import com.myassistant.errors.AppError
import com.myassistant.services.FileService
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.awt.{Color, Font, RenderingHints}
import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, File}
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

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

  private def createTestPng(text: String): Array[Byte] =
    val width  = 640
    val height = 120
    val img    = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g2d    = img.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    g2d.setColor(Color.WHITE)
    g2d.fillRect(0, 0, width, height)
    g2d.setColor(Color.BLACK)
    g2d.setFont(new Font("Serif", Font.PLAIN, 40))
    g2d.drawString(text, 20, 75)
    g2d.dispose()
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    baos.toByteArray()

  private def createTestPdf(text: String): Array[Byte] =
    val doc     = new PDDocument()
    val page    = new PDPage()
    doc.addPage(page)
    val font    = new PDType1Font(Standard14Fonts.FontName.HELVETICA)
    val content = new PDPageContentStream(doc, page)
    content.beginText()
    content.setFont(font, 12)
    content.newLineAtOffset(100f, 700f)
    content.showText(text)
    content.endText()
    content.close()
    val baos = new ByteArrayOutputStream()
    doc.save(baos)
    doc.close()
    baos.toByteArray()

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
              svc               <- ZIO.service[FileService]
              (filePath, _)     <- svc.upload("round.txt", "text/plain", encode(content))
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

          test("returns plain_text method and content for a .txt file") {
            val content = "Hello, world!"
            for
              svc            <- ZIO.service[FileService]
              (filePath, _)  <- svc.upload("note.txt", "text/plain", encode(content))
              (text, method) <- svc.extractText(filePath)
            yield assertTrue(text.contains("Hello")) &&
                  assertTrue(method == "plain_text")
          },

          test("returns plain_text method and content for a .md file") {
            val content = "# Heading\nSome markdown content."
            for
              svc            <- ZIO.service[FileService]
              (filePath, _)  <- svc.upload("readme.md", "text/markdown", encode(content))
              (text, method) <- svc.extractText(filePath)
            yield assertTrue(method == "plain_text") &&
                  assertTrue(text.contains("Heading"))
          },

          test("returns plain_text method and content for a .csv file") {
            val content = "name,age\nAlice,30\nBob,25"
            for
              svc            <- ZIO.service[FileService]
              (filePath, _)  <- svc.upload("data.csv", "text/csv", encode(content))
              (text, method) <- svc.extractText(filePath)
            yield assertTrue(method == "plain_text") &&
                  assertTrue(text.contains("Alice"))
          },

          test("returns plain_text method and content for a .json file") {
            val content = """{"key": "value"}"""
            for
              svc            <- ZIO.service[FileService]
              (filePath, _)  <- svc.upload("config.json", "application/json", encode(content))
              (text, method) <- svc.extractText(filePath)
            yield assertTrue(method == "plain_text") &&
                  assertTrue(text.contains("key"))
          },

          test("returns pdf_parser method and extracted text for a .pdf file") {
            val pdfBytes = createTestPdf("Salary slip March 2026")
            for
              svc            <- ZIO.service[FileService]
              (filePath, _)  <- svc.upload("slip.pdf", "application/pdf",
                                  Base64.getEncoder.encodeToString(pdfBytes))
              (text, method) <- svc.extractText(filePath)
            yield assertTrue(method == "pdf_parser") &&
                  assertTrue(text.contains("Salary slip March 2026"))
          },

          test("returns ValidationError for unsupported file type (.docx)") {
            for
              svc           <- ZIO.service[FileService]
              (filePath, _) <- svc.upload("report.docx", "application/vnd.openxmlformats", encode("fake"))
              result        <- svc.extractText(filePath).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("returns ValidationError for unsupported file type (.zip)") {
            for
              svc           <- ZIO.service[FileService]
              (filePath, _) <- svc.upload("archive.zip", "application/zip", encode("fake"))
              result        <- svc.extractText(filePath).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("attempts OCR for .jpg files (succeeds or fails with FileSystemError if tesseract unavailable)") {
            for
              svc           <- ZIO.service[FileService]
              (filePath, _) <- svc.upload("photo.jpg", "image/jpeg", encode("fake image bytes"))
              result        <- svc.extractText(filePath).exit
            yield result match
              case Exit.Success((_, method)) =>
                assertTrue(method == "ocr")
              case Exit.Failure(cause) =>
                // tesseract not installed — type was recognised but subprocess failed
                assert(cause.failureOption)(isSome(isSubtype[AppError.FileSystemError](anything)))
          },

          test("attempts OCR for .png files (succeeds or fails with FileSystemError if tesseract unavailable)") {
            for
              svc           <- ZIO.service[FileService]
              (filePath, _) <- svc.upload("screenshot.png", "image/png", encode("fake png"))
              result        <- svc.extractText(filePath).exit
            yield result match
              case Exit.Success((_, method)) =>
                assertTrue(method == "ocr")
              case Exit.Failure(cause) =>
                assert(cause.failureOption)(isSome(isSubtype[AppError.FileSystemError](anything)))
          },

          test("extracts correct text from a real PNG image via OCR") {
            val imageText = "Invoice 2026"
            for
              svc           <- ZIO.service[FileService]
              (filePath, _) <- svc.upload(
                                 "invoice.png", "image/png",
                                 Base64.getEncoder.encodeToString(createTestPng(imageText))
                               )
              result        <- svc.extractText(filePath).exit
            yield result match
              case Exit.Success((text, method)) =>
                assertTrue(method == "ocr") &&
                assertTrue(text.toLowerCase.replaceAll("[^a-z0-9 ]", "").contains("invoice"))
              case Exit.Failure(cause) =>
                // Tesseract not installed in this environment
                assert(cause.failureOption)(isSome(isSubtype[AppError.FileSystemError](anything)))
          },

        )
      ),

    )
