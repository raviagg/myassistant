package com.myassistant.services

import com.myassistant.api.models.{SourceSchemasResponse, UnifiedDataResponse}
import com.myassistant.db.repositories.UnifiedSchemaRepository
import com.myassistant.domain.{CreateUnifiedSchema, PatchUnifiedSchema, UnifiedSchema}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

trait UnifiedSchemaService:
  def create(req: CreateUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema]
  def get(id: UUID): ZIO[ZConnectionPool, AppError, UnifiedSchema]
  def list(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[UnifiedSchema]]
  def patch(id: UUID, req: PatchUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema]
  def delete(id: UUID): ZIO[ZConnectionPool, AppError, Unit]
  def sourceSchemas(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, SourceSchemasResponse]
  def sampleRows(sourceType: String, tableName: String, sourceConnectionId: Option[UUID], personId: Option[UUID], householdId: Option[UUID], limit: Int): ZIO[ZConnectionPool, AppError, List[io.circe.Json]]
  def data(id: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, UnifiedDataResponse]

object UnifiedSchemaService:

  final class Live(repo: UnifiedSchemaRepository) extends UnifiedSchemaService:

    def create(req: CreateUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema] =
      if req.personId.isEmpty && req.householdId.isEmpty then
        ZIO.fail(AppError.ValidationError("Either personId or householdId is required"))
      else if req.personId.isDefined && req.householdId.isDefined then
        ZIO.fail(AppError.ValidationError("Only one of personId or householdId may be set"))
      else
        val validStatuses = Set("proposed", "approved")
        if !validStatuses.contains(req.status) then
          ZIO.fail(AppError.ValidationError(s"status must be one of: ${validStatuses.mkString(", ")}"))
        else
          repo.create(req)

    def get(id: UUID): ZIO[ZConnectionPool, AppError, UnifiedSchema] =
      repo.findById(id).flatMap:
        case Some(s) => ZIO.succeed(s)
        case None    => ZIO.fail(AppError.NotFound("unified_schema", id.toString))

    def list(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[UnifiedSchema]] =
      repo.list(personId, householdId)

    def patch(id: UUID, req: PatchUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema] =
      val validStatuses = Set("proposed", "approved")
      req.status.filterNot(validStatuses.contains) match
        case Some(bad) => ZIO.fail(AppError.ValidationError(s"status '$bad' must be one of: ${validStatuses.mkString(", ")}"))
        case None =>
          repo.patch(id, req).flatMap:
            case Some(s) => ZIO.succeed(s)
            case None    => ZIO.fail(AppError.NotFound("unified_schema", id.toString))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Unit] =
      repo.delete(id).flatMap:
        case true  => ZIO.unit
        case false => ZIO.fail(AppError.NotFound("unified_schema", id.toString))

    def sourceSchemas(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, SourceSchemasResponse] =
      repo.sourceSchemas(personId, householdId)

    def sampleRows(sourceType: String, tableName: String, sourceConnectionId: Option[UUID], personId: Option[UUID], householdId: Option[UUID], limit: Int): ZIO[ZConnectionPool, AppError, List[io.circe.Json]] =
      repo.sampleRows(sourceType, tableName, sourceConnectionId, personId, householdId, limit)

    // TODO: repo.data also calls findById internally; thread schema through to avoid double fetch
    def data(id: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, UnifiedDataResponse] =
      get(id).flatMap(_ => repo.data(id, limit, offset))

  val live: ZLayer[UnifiedSchemaRepository, Nothing, UnifiedSchemaService] =
    ZLayer.fromFunction(new Live(_))
