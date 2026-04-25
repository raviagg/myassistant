package com.myassistant.services

import com.myassistant.db.repositories.SchemaRepository
import com.myassistant.domain.{EntityTypeSchema, ProposeEntityTypeSchema}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for entity type schema governance. */
trait SchemaService:
  /** Propose a new entity type schema (creates version 1 or bumps version). */
  def proposeSchema(req: ProposeEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema]

  /** Retrieve a schema version by id. */
  def getSchema(id: UUID): ZIO[ZConnectionPool, AppError, EntityTypeSchema]

  /** Return the current active schema for a (domain, entityType) pair. */
  def getCurrentSchema(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, EntityTypeSchema]

  /** List all current active schemas, optionally filtered by domain. */
  def listSchemas(domain: Option[String]): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]]

  /** Deactivate a schema version; blocks if facts reference it. */
  def deactivateSchema(id: UUID): ZIO[ZConnectionPool, AppError, Unit]

object SchemaService:

  /** Live implementation backed by SchemaRepository. */
  final class Live(repo: SchemaRepository) extends SchemaService:

    /** Propose a new schema version; the repository deactivates the previous active version atomically. */
    def proposeSchema(req: ProposeEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.create(req)

    /** Retrieve a schema version by id; fails with NotFound if absent. */
    def getSchema(id: UUID): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.findById(id).flatMap:
        case Some(s) => ZIO.succeed(s)
        case None    => ZIO.fail(AppError.NotFound("entity_type_schema", id.toString))

    /** Return the current active schema; fails with NotFound if no active schema exists for the pair. */
    def getCurrentSchema(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.findCurrent(domain, entityType).flatMap:
        case Some(s) => ZIO.succeed(s)
        case None    => ZIO.fail(AppError.NotFound("current_entity_type_schema", s"$domain/$entityType"))

    /** List all current active schemas, optionally filtered by domain. */
    def listSchemas(domain: Option[String]): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      repo.listCurrent(domain)

    /** Soft-delete (deactivate) a schema version by id; fails with NotFound if already inactive or missing. */
    def deactivateSchema(id: UUID): ZIO[ZConnectionPool, AppError, Unit] =
      repo.deactivate(id).flatMap:
        case true  => ZIO.unit
        case false => ZIO.fail(AppError.NotFound("active_entity_type_schema", id.toString))

  /** ZLayer providing the live SchemaService. */
  val live: ZLayer[SchemaRepository, Nothing, SchemaService] =
    ZLayer.fromFunction(new Live(_))
