package com.myassistant.services

import com.myassistant.db.repositories.DocumentRepository
import com.myassistant.domain.{CreateDocument, Document}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

trait DocumentService:
  def createDocument(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document]
  def getDocument(id: UUID): ZIO[ZConnectionPool, AppError, Document]
  def listDocuments(
      personId:      Option[UUID],
      householdId:   Option[UUID],
      sourceTypeId:  Option[UUID],
      createdAfter:  Option[String],
      createdBefore: Option[String],
      limit:         Int,
      offset:        Int,
  ): ZIO[ZConnectionPool, AppError, List[Document]]
  def searchDocuments(
      embedding:           List[Double],
      personId:            Option[UUID],
      householdId:         Option[UUID],
      sourceTypeId:        Option[UUID],
      limit:               Int,
      similarityThreshold: Double,
  ): ZIO[ZConnectionPool, AppError, List[(Document, Double)]]

object DocumentService:

  final class Live(repo: DocumentRepository) extends DocumentService:

    def createDocument(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document] =
      if req.personId.isEmpty && req.householdId.isEmpty then
        ZIO.fail(AppError.ValidationError("A document must be associated with a person or a household"))
      else
        repo.create(req)

    def getDocument(id: UUID): ZIO[ZConnectionPool, AppError, Document] =
      repo.findById(id).flatMap:
        case Some(d) => ZIO.succeed(d)
        case None    => ZIO.fail(AppError.NotFound("document", id.toString))

    def listDocuments(
        personId:      Option[UUID],
        householdId:   Option[UUID],
        sourceTypeId:  Option[UUID],
        createdAfter:  Option[String],
        createdBefore: Option[String],
        limit:         Int,
        offset:        Int,
    ): ZIO[ZConnectionPool, AppError, List[Document]] =
      repo.list(personId, householdId, sourceTypeId, createdAfter, createdBefore, limit, offset)

    def searchDocuments(
        embedding:           List[Double],
        personId:            Option[UUID],
        householdId:         Option[UUID],
        sourceTypeId:        Option[UUID],
        limit:               Int,
        similarityThreshold: Double,
    ): ZIO[ZConnectionPool, AppError, List[(Document, Double)]] =
      repo.searchBySimilarity(embedding, personId, householdId, sourceTypeId, limit, similarityThreshold)

  val live: ZLayer[DocumentRepository, Nothing, DocumentService] =
    ZLayer.fromFunction(new Live(_))
