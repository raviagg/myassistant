package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{ExtractTextRequest, ExtractTextResponse, FileUploadResponse, GetFileResponse, UploadFileRequest}
import com.myassistant.services.FileService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*

import java.util.Base64

object FileRoutes:

  val routes: Routes[FileService, Nothing] =
    Routes(
      // POST /api/v1/files — upload file (JSON body with base64 content)
      Method.POST / "api" / "v1" / "files" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[UploadFileRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(uploadReq) =>
                val mimeType = uploadReq.mimeType.getOrElse("application/octet-stream")
                ZIO.serviceWithZIO[FileService](_.upload(uploadReq.filename, mimeType, uploadReq.contentBase64))
                  .foldZIO(
                    err              => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    (filePath, size) => ZIO.succeed(Response.json(
                      FileUploadResponse(
                        filePath  = filePath,
                        filename  = uploadReq.filename,
                        mimeType  = mimeType,
                        sizeBytes = size,
                      ).asJson.noSpaces
                    ).status(Status.Created))
                  )
          yield response
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
                ZIO.serviceWithZIO[FileService](_.extractText(extractReq.filePath))
                  .foldZIO(
                    err            => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    (text, method) => ZIO.succeed(Response.json(
                      ExtractTextResponse(
                        filePath         = extractReq.filePath,
                        text             = text,
                        extractionMethod = method,
                      ).asJson.noSpaces
                    ))
                  )
          yield response
        },

      // GET /api/v1/files?path=<file-path> — retrieve file as base64 JSON
      Method.GET / "api" / "v1" / "files" ->
        handler { (req: Request) =>
          req.queryParam("path") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'path' is required"}"""
              ).status(Status.BadRequest))
            case Some(filePath) =>
              ZIO.serviceWithZIO[FileService](_.download(filePath))
                .foldZIO(
                  err                       => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  (bytes, mimeType, fname)  => ZIO.succeed(Response.json(
                    GetFileResponse(
                      filePath      = filePath,
                      contentBase64 = Base64.getEncoder.encodeToString(bytes),
                      mimeType      = mimeType,
                      filename      = fname,
                    ).asJson.noSpaces
                  ))
                )
        },

      // DELETE /api/v1/files?path=<file-path> — delete a file
      Method.DELETE / "api" / "v1" / "files" ->
        handler { (req: Request) =>
          req.queryParam("path") match
            case None =>
              ZIO.succeed(Response.json(
                """{"error":"bad_request","message":"Query parameter 'path' is required"}"""
              ).status(Status.BadRequest))
            case Some(filePath) =>
              ZIO.serviceWithZIO[FileService](_.delete(filePath))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  _   => ZIO.succeed(Response.status(Status.NoContent)),
                )
        },
    )
