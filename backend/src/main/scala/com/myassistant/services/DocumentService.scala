package com.myassistant.services

import com.myassistant.db.repositories.DocumentRepository
import com.myassistant.domain.{Document, CreateDocument}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for document ingestion and retrieval. */
trait DocumentService:
  /** Create an immutable document record. */
  def createDocument(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document]

  /** Retrieve a document by id; fails with NotFound if absent. */
  def getDocument(id: UUID): ZIO[ZConnectionPool, AppError, Document]

  /** List documents with optional filters and pagination. */
  def listDocuments(
      personId:    Option[UUID],
      householdId: Option[UUID],
      sourceType:  Option[String],
      limit:       Int,
      offset:      Int,
  ): ZIO[ZConnectionPool, AppError, List[Document]]

object DocumentService:

  /** Live implementation backed by DocumentRepository. */
  final class Live(repo: DocumentRepository) extends DocumentService:

    /** Create an immutable document; validates that at least one owner is set. */
    def createDocument(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document] =
      if req.personId.isEmpty && req.householdId.isEmpty then
        ZIO.fail(AppError.ValidationError("A document must be associated with a person or a household"))
      else
        repo.create(req)

    /** Retrieve a document by id; fails with NotFound if the record does not exist. */
    def getDocument(id: UUID): ZIO[ZConnectionPool, AppError, Document] =
      repo.findById(id).flatMap:
        case Some(d) => ZIO.succeed(d)
        case None    => ZIO.fail(AppError.NotFound("document", id.toString))

    /** List documents with optional owner and source-type filters, newest first. */
    def listDocuments(
        personId:    Option[UUID],
        householdId: Option[UUID],
        sourceType:  Option[String],
        limit:       Int,
        offset:      Int,
    ): ZIO[ZConnectionPool, AppError, List[Document]] =
      repo.list(personId, householdId, sourceType, limit, offset)

  /** ZLayer providing the live DocumentService. */
  val live: ZLayer[DocumentRepository, Nothing, DocumentService] =
    ZLayer.fromFunction(new Live(_))
