package com.myassistant.unit.services

import com.myassistant.api.models.{SourceSchemasResponse, UnifiedDataResponse}
import com.myassistant.db.repositories.UnifiedSchemaRepository
import com.myassistant.domain.{CreateUnifiedSchema, PatchUnifiedSchema, UnifiedSchema}
import com.myassistant.errors.AppError
import com.myassistant.services.UnifiedSchemaService
import io.circe.Json
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object UnifiedSchemaServiceSpec extends ZIOSpecDefault:

  final class MockUnifiedSchemaRepository(store: Ref[Map[UUID, UnifiedSchema]]) extends UnifiedSchemaRepository:

    private def now = Instant.now()

    def create(req: CreateUnifiedSchema): ZIO[ZConnectionPool, AppError, UnifiedSchema] =
      val s = UnifiedSchema(
        id               = UUID.randomUUID(),
        personId         = req.personId,
        householdId      = req.householdId,
        name             = req.name,
        description      = req.description,
        status           = req.status,
        fieldDefinitions = req.fieldDefinitions,
        createdAt        = now,
        updatedAt        = now,
      )
      store.update(_ + (s.id -> s)).as(s)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[UnifiedSchema]] =
      store.get.map(_.get(id))

    def list(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[UnifiedSchema]] =
      store.get.map: m =>
        m.values.toList
          .filter(s => personId.forall(p => s.personId.contains(p)))
          .filter(s => householdId.forall(h => s.householdId.contains(h)))

    def patch(id: UUID, req: PatchUnifiedSchema): ZIO[ZConnectionPool, AppError, Option[UnifiedSchema]] =
      store.get.flatMap: m =>
        m.get(id) match
          case None => ZIO.succeed(None)
          case Some(existing) =>
            val updated = existing.copy(
              name             = req.name.getOrElse(existing.name),
              description      = req.description.orElse(existing.description),
              status           = req.status.getOrElse(existing.status),
              fieldDefinitions = req.fieldDefinitions.getOrElse(existing.fieldDefinitions),
            )
            store.update(_ + (id -> updated)).as(Some(updated))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      store.modify: m =>
        if m.contains(id) then (true, m - id) else (false, m)

    def sourceSchemas(personId: Option[UUID], householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, SourceSchemasResponse] =
      import com.myassistant.api.models.*
      import com.myassistant.api.schemas.NativeSchemaRegistry
      val profile = SourceGroupResponse(None, "profile", "Profile", NativeSchemaRegistry.profileTables)
      ZIO.succeed(SourceSchemasResponse(profile = profile, sources = Nil))

    def data(id: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, UnifiedDataResponse] =
      ZIO.succeed(UnifiedDataResponse(items = Nil, total = 0, limit = limit, offset = offset))

  val mockRepoLayer: ZLayer[Any, Nothing, UnifiedSchemaRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, UnifiedSchema]).map(new MockUnifiedSchemaRepository(_)))

  private val personId    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  private val householdId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

  private def makeCreate(pId: Option[UUID] = Some(personId), hId: Option[UUID] = None, name: String = "transaction"): CreateUnifiedSchema =
    CreateUnifiedSchema(personId = pId, householdId = hId, name = name, description = None, status = "proposed", fieldDefinitions = Json.arr())

  private def withFreshService[E](spec: Spec[UnifiedSchemaService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, UnifiedSchemaService.live, ZConnectionPool.h2test.orDie)

  def spec: Spec[Any, Any] =
    suite("UnifiedSchemaServiceSpec")(

      withFreshService(
        suite("create")(

          test("creates a unified schema for a person") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.create(makeCreate())
            yield assertTrue(result.personId.contains(personId)) &&
                  assertTrue(result.name == "transaction") &&
                  assertTrue(result.status == "proposed")
          },

          test("creates a unified schema for a household") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.create(makeCreate(pId = None, hId = Some(householdId)))
            yield assertTrue(result.householdId.contains(householdId))
          },

          test("fails when neither personId nor householdId is set") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.create(makeCreate(pId = None, hId = None)).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("fails when both personId and householdId are set") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.create(makeCreate(pId = Some(personId), hId = Some(householdId))).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("fails on invalid status") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.create(makeCreate().copy(status = "bad_status")).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },
        )
      ),

      withFreshService(
        suite("get")(

          test("returns NotFound when schema does not exist") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.get(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns schema when it exists") {
            for
              svc     <- ZIO.service[UnifiedSchemaService]
              created <- svc.create(makeCreate())
              found   <- svc.get(created.id)
            yield assertTrue(found.id == created.id)
          },
        )
      ),

      withFreshService(
        suite("list")(

          test("returns empty list when no schemas exist") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.list(Some(personId), None)
            yield assertTrue(result.isEmpty)
          },

          test("returns schemas for a specific person") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              _      <- svc.create(makeCreate())
              _      <- svc.create(makeCreate(pId = None, hId = Some(householdId)))
              result <- svc.list(Some(personId), None)
            yield assertTrue(result.size == 1) &&
                  assertTrue(result.head.personId.contains(personId))
          },
        )
      ),

      withFreshService(
        suite("patch")(

          test("updates status to approved") {
            for
              svc     <- ZIO.service[UnifiedSchemaService]
              created <- svc.create(makeCreate())
              updated <- svc.patch(created.id, PatchUnifiedSchema(None, None, Some("approved"), None))
            yield assertTrue(updated.status == "approved")
          },

          test("returns NotFound for unknown id") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.patch(UUID.randomUUID(), PatchUnifiedSchema(None, None, None, None)).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("rejects invalid status in patch") {
            for
              svc     <- ZIO.service[UnifiedSchemaService]
              created <- svc.create(makeCreate())
              result  <- svc.patch(created.id, PatchUnifiedSchema(None, None, Some("garbage"), None)).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },
        )
      ),

      withFreshService(
        suite("delete")(

          test("deletes an existing schema") {
            for
              svc     <- ZIO.service[UnifiedSchemaService]
              created <- svc.create(makeCreate())
              _       <- svc.delete(created.id)
              result  <- svc.get(created.id).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns NotFound for unknown id") {
            for
              svc    <- ZIO.service[UnifiedSchemaService]
              result <- svc.delete(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },
        )
      ),
    )
