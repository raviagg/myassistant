package com.myassistant.unit.services

import com.myassistant.db.repositories.FactRepository
import com.myassistant.domain.{CreateFact, CurrentFact, Fact, OperationType}
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
        entityInstanceId = req.entityInstanceId,
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

    def findCurrentByEntityInstance(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, Option[CurrentFact]] =
      store.get.map: m =>
        val facts = m.values.filter(_.entityInstanceId == entityInstanceId).toList.sortBy(_.createdAt)
        facts.lastOption.flatMap: last =>
          if last.operationType == OperationType.Delete then None
          else
            val merged = facts.foldLeft(Json.obj()) { (acc, f) =>
              acc.deepMerge(f.fields)
            }
            Some(CurrentFact(entityInstanceId, last.schemaId, None, None, merged, last.createdAt))

    def listCurrent(
        personId:    Option[UUID],
        householdId: Option[UUID],
        domainId:    Option[UUID],
        entityType:  Option[String],
        limit:       Int,
        offset:      Int,
    ): ZIO[ZConnectionPool, AppError, List[CurrentFact]] =
      ZIO.succeed(Nil)

    def countCurrent(
        personId:    Option[UUID],
        householdId: Option[UUID],
        domainId:    Option[UUID],
        entityType:  Option[String],
    ): ZIO[ZConnectionPool, AppError, Long] =
      ZIO.succeed(0L)

    def searchCurrentBySimilarity(
        embedding:           List[Double],
        personId:            Option[UUID],
        householdId:         Option[UUID],
        domainId:            Option[UUID],
        entityType:          Option[String],
        limit:               Int,
        similarityThreshold: Double,
    ): ZIO[ZConnectionPool, AppError, List[(CurrentFact, Double)]] =
      ZIO.succeed(Nil)

  // ── Layer factory ─────────────────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, FactRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, Fact]).map(new MockFactRepository(_)))

  private def withFresh[E](spec: Spec[FactService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, FactService.live, ZConnectionPool.h2test.orDie)

  private val docId    = UUID.randomUUID()
  private val schemaId = UUID.randomUUID()

  private def req(entityId: UUID = UUID.randomUUID(), op: OperationType = OperationType.Create): CreateFact =
    CreateFact(
      documentId       = docId,
      schemaId         = schemaId,
      entityInstanceId = entityId,
      operationType    = op,
      fields           = Json.obj("title" -> Json.fromString("test")),
      embedding        = List.fill(1536)(0.0),
    )

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("FactServiceSpec")(

      withFresh(
        suite("createFact")(

          test("stores and returns a fact with given entityInstanceId") {
            val entityId = UUID.randomUUID()
            for
              svc    <- ZIO.service[FactService]
              result <- svc.createFact(req(entityId))
            yield assertTrue(result.documentId == docId) &&
                  assertTrue(result.schemaId == schemaId) &&
                  assertTrue(result.operationType == OperationType.Create) &&
                  assertTrue(result.entityInstanceId == entityId)
          },

          test("stores a Delete operation fact") {
            val entityId = UUID.randomUUID()
            for
              svc    <- ZIO.service[FactService]
              result <- svc.createFact(req(entityId, OperationType.Delete))
            yield assertTrue(result.operationType == OperationType.Delete)
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
              _    <- svc.createFact(req(entityId, OperationType.Create))
              _    <- svc.createFact(req(entityId, OperationType.Update))
              list <- svc.getEntityHistory(entityId)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.forall(_.entityInstanceId == entityId))
          },

        )
      ),

      withFresh(
        suite("getCurrentFact")(

          test("fails with NotFound when entity instance has no facts") {
            for
              svc    <- ZIO.service[FactService]
              result <- svc.getCurrentFact(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns merged current state for an entity instance") {
            val entityId = UUID.randomUUID()
            for
              svc     <- ZIO.service[FactService]
              _       <- svc.createFact(CreateFact(docId, schemaId, entityId, OperationType.Create,
                           Json.obj("title" -> Json.fromString("Task"), "status" -> Json.fromString("open")),
                           List.fill(1536)(0.0)))
              _       <- svc.createFact(CreateFact(docId, schemaId, entityId, OperationType.Update,
                           Json.obj("status" -> Json.fromString("done")),
                           List.fill(1536)(0.0)))
              current <- svc.getCurrentFact(entityId)
            yield assertTrue(current.entityInstanceId == entityId)
          },

        )
      ),

    )
