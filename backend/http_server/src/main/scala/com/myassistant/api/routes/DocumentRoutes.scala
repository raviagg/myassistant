package com.myassistant.api.routes

import com.myassistant.api.middleware.ErrorMiddleware
import com.myassistant.api.models.{CreateDocumentRequest, DocumentResponse, PagedResponse, SearchDocumentsRequest}
import com.myassistant.services.DocumentService
import io.circe.parser.decode
import io.circe.syntax.*
import zio.*
import zio.http.*
import zio.jdbc.*

import java.util.UUID
import scala.util.Try

object DocumentRoutes:

  val routes: Routes[DocumentService & ZConnectionPool, Nothing] =
    Routes(
      Method.POST / "api" / "v1" / "documents" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[CreateDocumentRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(createReq) =>
                ZIO.serviceWithZIO[DocumentService](_.createDocument(createReq.toDomain))
                  .foldZIO(
                    err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                    doc => ZIO.succeed(Response.json(DocumentResponse.fromDomain(doc).asJson.noSpaces).status(Status.Created)),
                  )
          yield response
        },

      Method.GET / "api" / "v1" / "documents" ->
        handler { (req: Request) =>
          val personId      = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId   = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val sourceTypeId  = req.queryParam("sourceTypeId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val createdAfter  = req.queryParam("createdAfter")
          val createdBefore = req.queryParam("createdBefore")
          val limit         = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
          val offset        = req.queryParam("offset").flatMap(_.toIntOption).getOrElse(0)
          ZIO.serviceWithZIO[DocumentService](_.listDocuments(personId, householdId, sourceTypeId, createdAfter, createdBefore, limit, offset))
            .foldZIO(
              err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
              docs =>
                ZIO.succeed(Response.json(
                  PagedResponse(
                    items  = docs.map(DocumentResponse.fromDomain),
                    total  = docs.size.toLong,
                    limit  = limit,
                    offset = offset,
                  ).asJson.noSpaces
                ))
            )
        },

      Method.GET / "api" / "v1" / "documents" / string("documentId") ->
        handler { (documentId: String, _: Request) =>
          Try(UUID.fromString(documentId)).toEither match
            case Left(_)   =>
              ZIO.succeed(Response.json(
                s"""{"error":"bad_request","message":"Invalid UUID: $documentId"}"""
              ).status(Status.BadRequest))
            case Right(id) =>
              ZIO.serviceWithZIO[DocumentService](_.getDocument(id))
                .foldZIO(
                  err => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  doc => ZIO.succeed(Response.json(DocumentResponse.fromDomain(doc).asJson.noSpaces)),
                )
        },

      Method.POST / "api" / "v1" / "documents" / "search" ->
        handler { (req: Request) =>
          for
            bodyStr  <- req.body.asString.orDie
            response <- decode[SearchDocumentsRequest](bodyStr) match
              case Left(err) =>
                ZIO.succeed(Response.json(
                  s"""{"error":"bad_request","message":"${err.getMessage.replace("\"", "'")}"}"""
                ).status(Status.BadRequest))
              case Right(searchReq) =>
                val limit     = searchReq.limit.getOrElse(10)
                val threshold = searchReq.similarityThreshold.getOrElse(0.7)
                ZIO.serviceWithZIO[DocumentService](
                  _.searchDocuments(searchReq.embedding, searchReq.personId, searchReq.householdId, searchReq.sourceTypeId, limit, threshold)
                ).foldZIO(
                  err   => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  items =>
                    import io.circe.Json
                    val jsonItems = items.map { case (doc, score) =>
                      DocumentResponse.fromDomain(doc).asJson.deepMerge(
                        io.circe.parser.parse(s"""{"similarity_score":$score}""").getOrElse(Json.obj())
                      )
                    }
                    ZIO.succeed(Response.json(
                      io.circe.Json.obj("items" -> io.circe.Json.arr(jsonItems*)).noSpaces
                    ))
                )
          yield response
        },
    )
