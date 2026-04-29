package com.myassistant.services

import com.myassistant.db.repositories.SchemaRepository
import com.myassistant.domain.{CreateEntityTypeSchema, CreateSchemaVersion, EntityTypeSchema}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

trait SchemaService:
  def createSchema(req: CreateEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema]
  def getSchema(id: UUID): ZIO[ZConnectionPool, AppError, EntityTypeSchema]
  def getCurrentSchema(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, EntityTypeSchema]
  def addVersion(domainId: UUID, entityType: String, req: CreateSchemaVersion): ZIO[ZConnectionPool, AppError, EntityTypeSchema]
  def listSchemas(domainId: Option[UUID], entityType: Option[String], activeOnly: Boolean): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]]
  def deactivateSchema(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Unit]

object SchemaService:

  final class Live(repo: SchemaRepository) extends SchemaService:

    def createSchema(req: CreateEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.create(req)

    def getSchema(id: UUID): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.findById(id).flatMap:
        case Some(s) => ZIO.succeed(s)
        case None    => ZIO.fail(AppError.NotFound("entity_type_schema", id.toString))

    def getCurrentSchema(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.findCurrent(domainId, entityType).flatMap:
        case Some(s) => ZIO.succeed(s)
        case None    => ZIO.fail(AppError.NotFound("current_entity_type_schema", s"$domainId/$entityType"))

    def addVersion(domainId: UUID, entityType: String, req: CreateSchemaVersion): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      repo.addVersion(domainId, entityType, req)

    def listSchemas(domainId: Option[UUID], entityType: Option[String], activeOnly: Boolean): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      repo.listSchemas(domainId, entityType, activeOnly)

    def deactivateSchema(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Unit] =
      repo.deactivate(domainId, entityType).flatMap:
        case true  => ZIO.unit
        case false => ZIO.fail(AppError.NotFound("active_entity_type_schema", s"$domainId/$entityType"))

  val live: ZLayer[SchemaRepository, Nothing, SchemaService] =
    ZLayer.fromFunction(new Live(_))
