package com.myassistant.unit.services

import com.myassistant.db.repositories.FactRepository
import com.myassistant.domain.{CreateFact, Fact, OperationType}
import com.myassistant.errors.AppError
import com.myassistant.services.FactService
import io.circe.Json
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object FactServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock FactRepository ─────────────────────────────────────────

  final class MockFactRepository(store: Ref[Map[UUID, Fact]]) extends FactRepository:

    def create(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact] =
      val fact = Fact(
        id               = UUID.randomUUID(),
        documentId       = req.documentId,
        schemaId         = req.schemaId,
        entityInstanceId = req.entityInstanceId.getOrElse(UUID.randomUUID()),
        operationType    = req.operationType,
        fields           = req.fields,
        createdAt        = Instant.now(),
      )
      store.update(_ + (fact.id -> fact)).as(fact)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Fact]] =
      store.get.map(_.get(id))

    def findByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      store.get.map(_.values.filter(_.entityInstanceId == entityInstanceId).toList.sortBy(_.createdAt))

    def findByDocument(documentId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      store.get.map(_.values.filter(_.documentId == documentId).toList.sortBy(_.createdAt))

    def findBySchema(schemaId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[Fact]] =
      store.get.map:
        _.values.filter(_.schemaId == schemaId).toList
          .sortBy(_.createdAt).reverse
          .slice(offset, offset + limit)

  // ── Layer factory ─────────────────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, FactRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, Fact]).map(new MockFactRepository(_)))

  private def withFresh[E](spec: Spec[FactService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, FactService.live, ZConnectionPool.h2test.orDie)

  private val docId    = UUID.randomUUID()
  private val schemaId = UUID.randomUUID()

  private def req(entityId: Option[UUID] = None, op: OperationType = OperationType.Create): CreateFact =
    CreateFact(
      documentId       = docId,
      schemaId         = schemaId,
      entityInstanceId = entityId,
      operationType    = op,
      fields           = Json.obj("title" -> Json.fromString("test")),
    )

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("FactServiceSpec")(

      withFresh(
        suite("createFact")(

          test("stores and returns a fact with generated UUID") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.createFact(req())
            yield assertTrue(result.documentId == docId) &&
                  assertTrue(result.schemaId == schemaId) &&
                  assertTrue(result.operationType == OperationType.Create)
          },

          test("uses provided entityInstanceId when given") {
            val entityId = UUID.randomUUID()
            for
              svc    <- ZIO.service[FactService]
              result <- svc.createFact(req(Some(entityId)))
            yield assertTrue(result.entityInstanceId == entityId)
          },

          test("generates a new entityInstanceId when None is given") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.createFact(req(None))
            yield assertTrue(result.entityInstanceId != null)
          },

        )
      ),

      withFresh(
        suite("getFact")(

          test("fails with NotFound when fact does not exist") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.getFact(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the fact when it exists") {
            for
              svc     <- ZIO.service[FactService]
              created <- svc.createFact(req())
              found   <- svc.getFact(created.id)
            yield assertTrue(found.id == created.id) &&
                  assertTrue(found.documentId == docId)
          },

        )
      ),

      withFresh(
        suite("getEntityHistory")(

          test("fails with NotFound when entity instance has no facts") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.getEntityHistory(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns all operations for an entity instance in order") {
            val entityId = UUID.randomUUID()
            for
              svc  <- ZIO.service[FactService]
              _    <- svc.createFact(req(Some(entityId), OperationType.Create))
              _    <- svc.createFact(req(Some(entityId), OperationType.Update))
              list <- svc.getEntityHistory(entityId)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.forall(_.entityInstanceId == entityId))
          },

        )
      ),

      withFresh(
        suite("getFactsByDocument")(

          test("returns empty list when document has no facts") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.getFactsByDocument(UUID.randomUUID())
            yield assertTrue(result.isEmpty)
          },

          test("returns all facts for a document") {
            val localDocId = UUID.randomUUID()
            val docReq = CreateFact(localDocId, schemaId, None, OperationType.Create,
                           Json.obj("x" -> Json.fromString("y")))
            for
              svc  <- ZIO.service[FactService]
              _    <- svc.createFact(docReq)
              _    <- svc.createFact(docReq)
              list <- svc.getFactsByDocument(localDocId)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.forall(_.documentId == localDocId))
          },

        )
      ),

      withFresh(
        suite("getFactsBySchema")(

          test("returns empty list when schema has no facts") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.getFactsBySchema(UUID.randomUUID(), 10, 0)
            yield assertTrue(result.isEmpty)
          },

          test("returns paginated facts for a schema") {
            val localSchemaId = UUID.randomUUID()
            val sReq = CreateFact(docId, localSchemaId, None, OperationType.Create,
                         Json.obj("a" -> Json.fromString("b")))
            for
              svc  <- ZIO.service[FactService]
              _    <- svc.createFact(sReq)
              _    <- svc.createFact(sReq)
              _    <- svc.createFact(sReq)
              page <- svc.getFactsBySchema(localSchemaId, 2, 0)
            yield assertTrue(page.size == 2) &&
                  assertTrue(page.forall(_.schemaId == localSchemaId))
          },

        )
      ),

    )
