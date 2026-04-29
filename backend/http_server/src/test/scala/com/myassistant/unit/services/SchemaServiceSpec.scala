package com.myassistant.unit.services

import com.myassistant.db.repositories.SchemaRepository
import com.myassistant.domain.{CreateEntityTypeSchema, CreateSchemaVersion, EntityTypeSchema}
import com.myassistant.errors.AppError
import com.myassistant.services.SchemaService
import io.circe.Json
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object SchemaServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock SchemaRepository ──────────────────────────

  final class MockSchemaRepository(store: Ref[Map[UUID, EntityTypeSchema]]) extends SchemaRepository:

    private def now = Instant.now()

    private def rowFromCreate(req: CreateEntityTypeSchema, version: Int): EntityTypeSchema =
      EntityTypeSchema(
        id               = UUID.randomUUID(),
        domainId         = req.domainId,
        entityType       = req.entityType,
        schemaVersion    = version,
        description      = req.description,
        fieldDefinitions = req.fieldDefinitions,
        mandatoryFields  = Nil,
        isActive         = true,
        createdAt        = now,
        updatedAt        = now,
      )

    def create(req: CreateEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      store.get.flatMap: m =>
        val prevVersion = m.values
          .filter(s => s.domainId == req.domainId && s.entityType == req.entityType)
          .maxByOption(_.schemaVersion)
          .map(_.schemaVersion)
          .getOrElse(0)
        val schema = rowFromCreate(req, prevVersion + 1)
        store.update(_ + (schema.id -> schema)).as(schema)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      store.get.map(_.get(id))

    def findCurrent(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      store.get.map: m =>
        m.values.filter(s => s.domainId == domainId && s.entityType == entityType && s.isActive)
          .maxByOption(_.schemaVersion)

    def addVersion(domainId: UUID, entityType: String, req: CreateSchemaVersion): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      store.get.flatMap: m =>
        val prevVersion = m.values
          .filter(s => s.domainId == domainId && s.entityType == entityType)
          .maxByOption(_.schemaVersion)
          .map(_.schemaVersion)
          .getOrElse(0)
        val schema = EntityTypeSchema(
          id               = UUID.randomUUID(),
          domainId         = domainId,
          entityType       = entityType,
          schemaVersion    = prevVersion + 1,
          description      = req.description,
          fieldDefinitions = req.fieldDefinitions,
          mandatoryFields  = Nil,
          isActive         = true,
          createdAt        = now,
          updatedAt        = now,
        )
        store.update(_ + (schema.id -> schema)).as(schema)

    def listSchemas(domainId: Option[UUID], entityType: Option[String], activeOnly: Boolean): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      store.get.map: m =>
        m.values.toList
          .filter(s => domainId.forall(_ == s.domainId))
          .filter(s => entityType.forall(_ == s.entityType))
          .filter(s => !activeOnly || s.isActive)

    def deactivate(domainId: UUID, entityType: String): ZIO[ZConnectionPool, AppError, Boolean] =
      store.get.flatMap: m =>
        val active = m.values.filter(s => s.domainId == domainId && s.entityType == entityType && s.isActive).toList
        if active.isEmpty then ZIO.succeed(false)
        else
          val updates = active.map(s => s.id -> s.copy(isActive = false))
          store.update(m => m ++ updates).as(true)

  // ── Layer factory ─────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, SchemaRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, EntityTypeSchema]).map(new MockSchemaRepository(_)))

  private val healthDomainId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  private val todoDomainId   = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
  private val finDomainId    = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
  private val empDomainId    = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")

  private def makeReq(domainId: UUID, entityType: String): CreateEntityTypeSchema =
    CreateEntityTypeSchema(
      domainId         = domainId,
      entityType       = entityType,
      fieldDefinitions = Json.arr(),
      description      = Some(s"$entityType schema"),
    )

  private def withFreshService[E](spec: Spec[SchemaService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, SchemaService.live, ZConnectionPool.h2test.orDie)

  // ── Tests ─────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("SchemaServiceSpec")(

      withFreshService(
        suite("createSchema")(

          test("creates schema version 1 for a new entity type") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.createSchema(makeReq(healthDomainId, "insurance_card"))
            yield assertTrue(result.domainId == healthDomainId) &&
                  assertTrue(result.entityType == "insurance_card") &&
                  assertTrue(result.schemaVersion == 1) &&
                  assertTrue(result.isActive)
          },

          test("creates version 2 when a version 1 already exists") {
            for
              svc <- ZIO.service[SchemaService]
              _   <- svc.createSchema(makeReq(todoDomainId, "todo_item"))
              v2  <- svc.createSchema(makeReq(todoDomainId, "todo_item"))
            yield assertTrue(v2.schemaVersion == 2)
          },

        )
      ),

      withFreshService(
        suite("getSchema")(

          test("returns NotFound when schema does not exist") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.getSchema(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns schema when it exists") {
            for
              svc     <- ZIO.service[SchemaService]
              created <- svc.createSchema(makeReq(empDomainId, "job"))
              found   <- svc.getSchema(created.id)
            yield assertTrue(found.id == created.id) &&
                  assertTrue(found.entityType == "job")
          },

        )
      ),

      withFreshService(
        suite("getCurrentSchema")(

          test("returns NotFound when no active schema exists") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.getCurrentSchema(UUID.randomUUID(), "unknown").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the current active schema") {
            for
              svc     <- ZIO.service[SchemaService]
              created <- svc.createSchema(makeReq(finDomainId, "payslip"))
              current <- svc.getCurrentSchema(finDomainId, "payslip")
            yield assertTrue(current.id == created.id)
          },

        )
      ),

      withFreshService(
        suite("listSchemas")(

          test("returns empty list when no schemas exist") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.listSchemas(None, None, true)
            yield assertTrue(result.isEmpty)
          },

          test("returns all active schemas") {
            for
              svc  <- ZIO.service[SchemaService]
              _    <- svc.createSchema(makeReq(healthDomainId, "insurance_card"))
              _    <- svc.createSchema(makeReq(todoDomainId, "todo_item"))
              list <- svc.listSchemas(None, None, true)
            yield assertTrue(list.size == 2)
          },

          test("filters by domainId") {
            for
              svc  <- ZIO.service[SchemaService]
              _    <- svc.createSchema(makeReq(healthDomainId, "insurance_card"))
              _    <- svc.createSchema(makeReq(todoDomainId, "todo_item"))
              list <- svc.listSchemas(Some(healthDomainId), None, true)
            yield assertTrue(list.size == 1) &&
                  assertTrue(list.head.domainId == healthDomainId)
          },

        )
      ),

      withFreshService(
        suite("deactivateSchema")(

          test("deactivates an active schema") {
            for
              svc     <- ZIO.service[SchemaService]
              created <- svc.createSchema(makeReq(healthDomainId, "insurance_card"))
              _       <- svc.deactivateSchema(healthDomainId, "insurance_card")
              result  <- svc.getCurrentSchema(healthDomainId, "insurance_card").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns NotFound when no active schema exists for the given domainId/entityType") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.deactivateSchema(UUID.randomUUID(), "unknown").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

    )
