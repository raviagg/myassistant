package com.myassistant.unit.services

import com.myassistant.db.repositories.SchemaRepository
import com.myassistant.domain.{EntityTypeSchema, ProposeEntityTypeSchema}
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

    private def rowFromReq(req: ProposeEntityTypeSchema, version: Int): EntityTypeSchema =
      EntityTypeSchema(
        id                = UUID.randomUUID(),
        domain            = req.domain,
        entityType        = req.entityType,
        schemaVersion     = version,
        description       = req.description,
        fieldDefinitions  = req.fieldDefinitions,
        mandatoryFields   = Nil,
        extractionPrompt  = req.extractionPrompt,
        isActive          = true,
        changeDescription = req.changeDescription,
        createdAt         = now,
      )

    def create(req: ProposeEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
      store.get.flatMap: m =>
        val prevVersion = m.values
          .filter(s => s.domain == req.domain && s.entityType == req.entityType)
          .maxByOption(_.schemaVersion)
          .map(_.schemaVersion)
          .getOrElse(0)
        val schema = rowFromReq(req, prevVersion + 1)
        store.update(_ + (schema.id -> schema)).as(schema)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      store.get.map(_.get(id))

    def findCurrent(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
      store.get.map: m =>
        m.values.filter(s => s.domain == domain && s.entityType == entityType && s.isActive)
          .maxByOption(_.schemaVersion)

    def findAll(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      store.get.map: m =>
        m.values.filter(s => s.domain == domain && s.entityType == entityType).toList

    def listCurrent(domain: Option[String]): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
      store.get.map: m =>
        val active = m.values.filter(_.isActive)
        domain.fold(active.toList)(d => active.filter(_.domain == d).toList)

    def deactivate(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      store.get.flatMap: m =>
        m.get(id).filter(_.isActive) match
          case None    => ZIO.succeed(false)
          case Some(s) => store.update(_ + (id -> s.copy(isActive = false))).as(true)

  // ── Layer factory ─────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, SchemaRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, EntityTypeSchema]).map(new MockSchemaRepository(_)))

  private def makeReq(domain: String, entityType: String): ProposeEntityTypeSchema =
    ProposeEntityTypeSchema(
      domain            = domain,
      entityType        = entityType,
      description       = s"$entityType schema",
      fieldDefinitions  = Json.arr(),
      extractionPrompt  = s"Extract $entityType facts",
      changeDescription = None,
    )

  private def withFreshService[E](spec: Spec[SchemaService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, SchemaService.live, ZConnectionPool.h2test.orDie)

  // ── Tests ─────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("SchemaServiceSpec")(

      withFreshService(
        suite("proposeSchema")(

          test("creates schema version 1 for a new entity type") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.proposeSchema(makeReq("health", "insurance_card"))
            yield assertTrue(result.domain == "health") &&
                  assertTrue(result.entityType == "insurance_card") &&
                  assertTrue(result.schemaVersion == 1) &&
                  assertTrue(result.isActive)
          },

          test("creates version 2 when a version 1 already exists") {
            for
              svc <- ZIO.service[SchemaService]
              _   <- svc.proposeSchema(makeReq("todo", "todo_item"))
              v2  <- svc.proposeSchema(makeReq("todo", "todo_item"))
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
              created <- svc.proposeSchema(makeReq("employment", "job"))
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
              result <- svc.getCurrentSchema("unknown", "unknown").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the current active schema") {
            for
              svc     <- ZIO.service[SchemaService]
              created <- svc.proposeSchema(makeReq("finance", "payslip"))
              current <- svc.getCurrentSchema("finance", "payslip")
            yield assertTrue(current.id == created.id)
          },

        )
      ),

      withFreshService(
        suite("listSchemas")(

          test("returns empty list when no schemas exist") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.listSchemas(None)
            yield assertTrue(result.isEmpty)
          },

          test("returns all active schemas") {
            for
              svc  <- ZIO.service[SchemaService]
              _    <- svc.proposeSchema(makeReq("health", "insurance_card"))
              _    <- svc.proposeSchema(makeReq("todo", "todo_item"))
              list <- svc.listSchemas(None)
            yield assertTrue(list.size == 2)
          },

          test("filters by domain") {
            for
              svc  <- ZIO.service[SchemaService]
              _    <- svc.proposeSchema(makeReq("health", "insurance_card"))
              _    <- svc.proposeSchema(makeReq("todo", "todo_item"))
              list <- svc.listSchemas(Some("health"))
            yield assertTrue(list.size == 1) &&
                  assertTrue(list.head.domain == "health")
          },

        )
      ),

      withFreshService(
        suite("deactivateSchema")(

          test("deactivates an active schema") {
            for
              svc     <- ZIO.service[SchemaService]
              created <- svc.proposeSchema(makeReq("health", "insurance_card"))
              _       <- svc.deactivateSchema(created.id)
              result  <- svc.getCurrentSchema("health", "insurance_card").exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns NotFound when schema does not exist or already inactive") {
            for
              svc    <- ZIO.service[SchemaService]
              result <- svc.deactivateSchema(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

    )
