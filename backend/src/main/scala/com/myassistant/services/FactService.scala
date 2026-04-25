package com.myassistant.services

import com.myassistant.db.repositories.FactRepository
import com.myassistant.domain.{Fact, CreateFact}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for the append-only fact operation stream. */
trait FactService:
  /** Append a new fact operation row; generates entityInstanceId for creates. */
  def createFact(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact]

  /** Retrieve a fact row by id; fails with NotFound if absent. */
  def getFact(id: UUID): ZIO[ZConnectionPool, AppError, Fact]

  /** Return the full operation history for a logical entity instance. */
  def getEntityHistory(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]

  /** Return all fact rows derived from a given document. */
  def getFactsByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]

  /** Return all fact rows for a given schema version, with pagination. */
  def getFactsBySchema(schemaId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[Fact]]

object FactService:

  /** Live implementation backed by FactRepository. */
  final class Live(repo: FactRepository) extends FactService:

    /** Append a new fact operation row; repository generates entityInstanceId when None. */
    def createFact(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact] =
      repo.create(req)

    /** Retrieve a fact row by id; fails with NotFound if the record does not exist. */
    def getFact(id: UUID): ZIO[ZConnectionPool, AppError, Fact] =
      repo.findById(id).flatMap:
        case Some(f) => ZIO.succeed(f)
        case None    => ZIO.fail(AppError.NotFound("fact", id.toString))

    /** Return all fact operation rows for an entity instance, newest first; fails with NotFound if none exist. */
    def getEntityHistory(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      repo.findByEntityInstance(entityInstanceId).flatMap:
        case Nil  => ZIO.fail(AppError.NotFound("entity_instance", entityInstanceId.toString))
        case rows => ZIO.succeed(rows)

    /** Return all fact rows produced from a given document, oldest first. */
    def getFactsByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      repo.findByDocument(documentId)

    /** Return fact rows for a schema version with pagination, newest first. */
    def getFactsBySchema(schemaId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[Fact]] =
      repo.findBySchema(schemaId, limit, offset)

  /** ZLayer providing the live FactService. */
  val live: ZLayer[FactRepository, Nothing, FactService] =
    ZLayer.fromFunction(new Live(_))
