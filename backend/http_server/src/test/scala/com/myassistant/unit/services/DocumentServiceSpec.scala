package com.myassistant.unit.services

import com.myassistant.db.repositories.DocumentRepository
import com.myassistant.domain.{CreateDocument, Document}
import com.myassistant.errors.AppError
import com.myassistant.services.DocumentService
import io.circe.Json
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object DocumentServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock DocumentRepository ────────────────────────────────────

  final class MockDocumentRepository(store: Ref[Map[UUID, Document]]) extends DocumentRepository:

    def create(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document] =
      val now = Instant.now()
      val doc = Document(
        id            = UUID.randomUUID(),
        personId      = req.personId,
        householdId   = req.householdId,
        contentText   = req.contentText,
        sourceTypeId  = req.sourceTypeId,
        files         = req.files,
        supersedesIds = req.supersedesIds,
        createdAt     = now,
      )
      store.update(_ + (doc.id -> doc)).as(doc)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Document]] =
      store.get.map(_.get(id))

    def list(
        personId:      Option[UUID],
        householdId:   Option[UUID],
        sourceTypeId:  Option[UUID],
        createdAfter:  Option[String],
        createdBefore: Option[String],
        limit:         Int,
        offset:        Int,
    ): ZIO[ZConnectionPool, AppError, List[Document]] =
      store.get.map: m =>
        m.values.toList
          .filter(d => personId.forall(p => d.personId.contains(p)))
          .filter(d => householdId.forall(h => d.householdId.contains(h)))
          .filter(d => sourceTypeId.forall(st => d.sourceTypeId == st))
          .sortBy(_.createdAt).reverse
          .slice(offset, offset + limit)

    def count(
        personId:     Option[UUID],
        householdId:  Option[UUID],
        sourceTypeId: Option[UUID],
    ): ZIO[ZConnectionPool, AppError, Long] =
      store.get.map: m =>
        m.values
          .filter(d => personId.forall(p => d.personId.contains(p)))
          .filter(d => householdId.forall(h => d.householdId.contains(h)))
          .filter(d => sourceTypeId.forall(st => d.sourceTypeId == st))
          .size.toLong

    def searchBySimilarity(
        embedding:           List[Double],
        personId:            Option[UUID],
        householdId:         Option[UUID],
        sourceTypeId:        Option[UUID],
        limit:               Int,
        similarityThreshold: Double,
    ): ZIO[ZConnectionPool, AppError, List[(Document, Double)]] =
      ZIO.succeed(Nil)

  // ── Layer factory ─────────────────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, DocumentRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, Document]).map(new MockDocumentRepository(_)))

  private def withFresh[E](spec: Spec[DocumentService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, DocumentService.live, ZConnectionPool.h2test.orDie)

  private val sourceTypeId = UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val otherSourceTypeId = UUID.fromString("22222222-2222-2222-2222-222222222222")

  private def personDoc(personId: UUID, text: String = "content"): CreateDocument =
    CreateDocument(
      personId      = Some(personId),
      householdId   = None,
      contentText   = text,
      sourceTypeId  = sourceTypeId,
      embedding     = List.fill(1536)(0.0),
      files         = Json.arr(),
      supersedesIds = Nil,
    )

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("DocumentServiceSpec")(

      withFresh(
        suite("createDocument")(

          test("stores and returns a document for a valid person owner") {
            val personId = UUID.randomUUID()
            for
              svc    <- ZIO.service[DocumentService]
              result <- svc.createDocument(personDoc(personId, "hello world"))
            yield assertTrue(result.contentText == "hello world") &&
                  assertTrue(result.personId.contains(personId))
          },

          test("fails with ValidationError when neither personId nor householdId is set") {
            val req = CreateDocument(None, None, "orphan", sourceTypeId, List.fill(1536)(0.0), Json.arr(), Nil)
            for
              svc    <- ZIO.service[DocumentService]
              result <- svc.createDocument(req).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("stores document for a household owner") {
            val householdId = UUID.randomUUID()
            val req = CreateDocument(None, Some(householdId), "household doc", sourceTypeId, List.fill(1536)(0.0), Json.arr(), Nil)
            for
              svc    <- ZIO.service[DocumentService]
              result <- svc.createDocument(req)
            yield assertTrue(result.householdId.contains(householdId)) &&
                  assertTrue(result.personId.isEmpty)
          },

        )
      ),

      withFresh(
        suite("getDocument")(

          test("returns NotFound when document does not exist") {
            for
              svc    <- ZIO.service[DocumentService]
              result <- svc.getDocument(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the document when it exists") {
            val personId = UUID.randomUUID()
            for
              svc     <- ZIO.service[DocumentService]
              created <- svc.createDocument(personDoc(personId, "get me"))
              found   <- svc.getDocument(created.id)
            yield assertTrue(found.id == created.id) &&
                  assertTrue(found.contentText == "get me")
          },

        )
      ),

      withFresh(
        suite("listDocuments")(

          test("returns empty list when no documents exist") {
            for
              svc    <- ZIO.service[DocumentService]
              result <- svc.listDocuments(Some(UUID.randomUUID()), None, None, None, None, 10, 0)
            yield assertTrue(result.isEmpty)
          },

          test("returns documents for a person filtered by sourceTypeId") {
            val personId = UUID.randomUUID()
            for
              svc  <- ZIO.service[DocumentService]
              _    <- svc.createDocument(CreateDocument(Some(personId), None, "user doc",  sourceTypeId,      List.fill(1536)(0.0), Json.arr(), Nil))
              _    <- svc.createDocument(CreateDocument(Some(personId), None, "plaid doc", otherSourceTypeId, List.fill(1536)(0.0), Json.arr(), Nil))
              list <- svc.listDocuments(Some(personId), None, Some(sourceTypeId), None, None, 10, 0)
            yield assertTrue(list.size == 1) &&
                  assertTrue(list.head.sourceTypeId == sourceTypeId)
          },

          test("paginates with limit and offset") {
            val personId = UUID.randomUUID()
            for
              svc  <- ZIO.service[DocumentService]
              _    <- svc.createDocument(personDoc(personId, "doc 1"))
              _    <- svc.createDocument(personDoc(personId, "doc 2"))
              _    <- svc.createDocument(personDoc(personId, "doc 3"))
              page <- svc.listDocuments(Some(personId), None, None, None, None, 2, 0)
            yield assertTrue(page.size == 2)
          },

        )
      ),

    )
