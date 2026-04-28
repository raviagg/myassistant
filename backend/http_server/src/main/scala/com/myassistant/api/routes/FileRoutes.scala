package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{ExtractTextRequest, ExtractTextResponse, FileUploadResponse}
import com.myassistant.errors.AppError
import com.myassistant.services.FileService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/** HTTP routes for file upload and retrieval.
 *
 *  POST   /api/v1/files                    — upload a file (multipart form-data or raw body)
 *  POST   /api/v1/files/extract-text       — extract text from a stored file
 *  GET    /api/v1/files                    — download file by storage key (query: key)
 *  DELETE /api/v1/files                    — delete a file (query: key)
 */
object FileRoutes:

  /** Build file handling routes requiring FileService in the environment. */
  val routes: Routes[FileService, Nothing] =
    Routes(
      // POST /api/v1/files — upload via multipart or raw body
      Method.POST / "api" / "v1" / "files" ->
        handler { (req: Request) =>
          val personId    = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val fileName    = req.queryParam("fileName").getOrElse("upload")
          val mimeType    = req.queryParam("mimeType")
            .orElse(req.header(Header.ContentType).map(_.mediaType.fullType))
            .getOrElse("application/octet-stream")

          req.body.asMultipartForm.foldZIO(
            _ =>
              // Fall back to treating the whole body as the file content
              for
                chunk    <- req.body.asChunk.orDie
                bytes     = chunk.toArray
                response <- ZIO.serviceWithZIO[FileService](_.upload(personId, householdId, fileName, mimeType, bytes))
                              .foldZIO(
                                err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                                key => ZIO.succeed(Response.json(
                                  FileUploadResponse(
                                    key          = key,
                                    originalName = fileName,
                                    mimeType     = mimeType,
                                    size         = bytes.length.toLong,
                                  ).asJson.noSpaces
                                ).status(Status.Created))
                              )
              yield response,
            form =>
              form.get("file") match
                case None =>
                  ZIO.succeed(Response.json(
                    """{"error":"bad_request","message":"Multipart field 'file' is required"}"""
                  ).status(Status.BadRequest))
                case Some(field) =>
                  val fieldName = field.filename.getOrElse(fileName)
                  val fieldMime = field.contentType.fullType
                  for
                    chunk    <- field.asChunk
                    bytes     = chunk.toArray
                    response <- ZIO.serviceWithZIO[FileService](_.upload(personId, householdId, fieldName, fieldMime, bytes))
                                  .foldZIO(
                                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                                    key => ZIO.succeed(Response.json(
                                      FileUploadResponse(
                                        key          = key,
                                        originalName = fieldName,
                                        mimeType     = fieldMime,
                                        size         = bytes.length.toLong,
                                      ).asJson.noSpaces
                                    ).status(Status.Created))
                                  )
                  yield response
          )
        },

      // POST /api/v1/files/extract-text — extract text content from a stored file
      Method.POST / "api" / "v1" / "files" / "extract-text" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[ExtractTextRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(extractReq) =>
                ZIO.serviceWithZIO[FileService](_.download(extractReq.key))
                  .foldZIO(
                    err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    bytes =>
                      // Best-effort UTF-8 decode; OCR/PDF extraction is a future enhancement
                      val text = new String(bytes, StandardCharsets.UTF_8)
                        .replaceAll("[^\\x09\\x0A\\x0D\\x20-\\x7E\\u00A0-\\uD7FF\\uE000-\\uFFFD]", " ")
                        .trim
                      ZIO.succeed(Response.json(
                        ExtractTextResponse(key = extractReq.key, extractedText = text).asJson.noSpaces
                      ))
                  )
          yield response
        },

      // GET /api/v1/files?key=<storage-key> — download file bytes
      Method.GET / "api" / "v1" / "files" ->
        handler { (req: Request) =>
          req.queryParam("key") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'key' is required"}"""
              ).status(Status.BadRequest))
            case Some(key) =>
              ZIO.serviceWithZIO[FileService](_.download(key))
                .foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  bytes => ZIO.succeed(
                    Response(
                      status  = Status.Ok,
                      headers = Headers(Header.ContentType(MediaType.application.`octet-stream`)),
                      body    = Body.fromArray(bytes),
                    )
                  )
                )
        },

      // DELETE /api/v1/files?key=<storage-key> — delete a file from local storage
      Method.DELETE / "api" / "v1" / "files" ->
        handler { (req: Request) =>
          req.queryParam("key") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'key' is required"}"""
              ).status(Status.BadRequest))
            case Some(key) =>
              ZIO.attempt {
                val path = java.nio.file.Paths.get(key)
                java.nio.file.Files.deleteIfExists(path)
              }.foldZIO(
                err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(AppError.FileSystemError(err))),
                _   => ZIO.succeed(Response.status(Status.NoContent))
              )
        },
    )
