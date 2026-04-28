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

/** HTTP routes for document management.
 *
 *  POST   /api/v1/documents          — ingest a new document
 *  GET    /api/v1/documents          — list documents (query: personId, householdId, sourceType, limit, offset)
 *  GET    /api/v1/documents/:id      — get a document by id
 *  POST   /api/v1/documents/search   — full-text / semantic search (falls back to listDocuments)
 */
object DocumentRoutes:

  /** Build document routes requiring DocumentService and ZConnectionPool in the environment. */
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
          val personId    = req.queryParam("personId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val householdId = req.queryParam("householdId").flatMap(s => Try(UUID.fromString(s)).toOption)
          val sourceType  = req.queryParam("sourceType")
          val limit       = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
          val offset      = req.queryParam("offset").flatMap(_.toIntOption).getOrElse(0)
          ZIO.serviceWithZIO[DocumentService](_.listDocuments(personId, householdId, sourceType, limit, offset))
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
                // DocumentService has no vector search yet; fall back to listDocuments filtered by owner
                val limit  = searchReq.limit.getOrElse(20)
                ZIO.serviceWithZIO[DocumentService](
                  _.listDocuments(searchReq.personId, searchReq.householdId, None, limit, 0)
                ).foldZIO(
                  err  => ZIO.succeed(ErrorMiddleware.appErrorToResponse(err)),
                  docs =>
                    ZIO.succeed(Response.json(
                      PagedResponse(
                        items  = docs.map(DocumentResponse.fromDomain),
                        total  = docs.size.toLong,
                        limit  = limit,
                        offset = 0,
                      ).asJson.noSpaces
                    ))
                )
          yield response
        },
    )
