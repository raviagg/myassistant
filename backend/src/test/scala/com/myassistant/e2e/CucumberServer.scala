package com.myassistant.e2e

import com.myassistant.api.routes.{DocumentRoutes, FactRoutes, PersonRoutes, SchemaRoutes}
import com.myassistant.db.repositories.{DocumentRepository, FactRepository, PersonRepository, SchemaRepository}
import com.myassistant.domain.*
import com.myassistant.errors.AppError
import com.myassistant.services.{DocumentService, FactService, PersonService, SchemaService}
import io.circe.Json
import zio.*
import zio.http.*
import zio.jdbc.ZConnectionPool

import java.time.Instant
import java.util.UUID

object CucumberServer:

  val TestPort = 8181

  private val minimalRoutes =
    Routes(
      Method.GET / "health" -> handler(Response.json("""{"status":"ok","version":"0.1.0"}""")),
    ) ++ PersonRoutes.routes ++ SchemaRoutes.routes ++ DocumentRoutes.routes ++ FactRoutes.routes

  // ── Seeded schema (mirrors V5 migration seed) ───────────────────────────────
  private val seededSchemaId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val seededSchema = EntityTypeSchema(
    id               = seededSchemaId,
    domain           = "todo",
    entityType       = "todo_item",
    schemaVersion    = 1,
    description      = "A single to-do or reminder item",
    fieldDefinitions = Json.arr(
      Json.obj("name" -> Json.fromString("title"),  "type" -> Json.fromString("text"), "mandatory" -> Json.fromBoolean(true)),
      Json.obj("name" -> Json.fromString("status"), "type" -> Json.fromString("text"), "mandatory" -> Json.fromBoolean(false)),
    ),
    mandatoryFields  = List("title"),
    extractionPrompt = "Extract todo items from the document.",
    isActive         = true,
    changeDescription = None,
    createdAt        = Instant.parse("2024-01-01T00:00:00Z"),
  )

  // ── In-memory PersonRepository ───────────────────────────────────────────────
  private val personRepoLayer: ZLayer[Any, Nothing, PersonRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[UUID, Person]).map(store =>
        new PersonRepository:
          def create(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person] =
            val now = Instant.now()
            val p   = Person(UUID.randomUUID(), req.fullName, req.gender, req.dateOfBirth,
                             req.preferredName, req.userIdentifier, now, now)
            store.update(_ + (p.id -> p)).as(p)
          def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Person]] =
            store.get.map(_.get(id))
          def findByUserIdentifier(identifier: String): ZIO[ZConnectionPool, AppError, Option[Person]] =
            store.get.map(_.values.find(_.userIdentifier.contains(identifier)))
          def listAll(householdId: Option[UUID]): ZIO[ZConnectionPool, AppError, List[Person]] =
            store.get.map(_.values.toList.sortBy(_.fullName))
          def update(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Option[Person]] =
            store.get.flatMap: m =>
              m.get(id) match
                case None => ZIO.succeed(None)
                case Some(existing) =>
                  val u = existing.copy(
                    fullName       = patch.fullName.getOrElse(existing.fullName),
                    gender         = patch.gender.getOrElse(existing.gender),
                    dateOfBirth    = patch.dateOfBirth.orElse(existing.dateOfBirth),
                    preferredName  = patch.preferredName.orElse(existing.preferredName),
                    userIdentifier = patch.userIdentifier.orElse(existing.userIdentifier),
                    updatedAt      = Instant.now(),
                  )
                  store.update(_ + (id -> u)).as(Some(u))
          def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
            store.get.flatMap: m =>
              if m.contains(id) then store.update(_ - id).as(true)
              else ZIO.succeed(false)
      )
    )

  // ── In-memory SchemaRepository (pre-seeded) ─────────────────────────────────
  private val schemaRepoLayer: ZLayer[Any, Nothing, SchemaRepository] =
    ZLayer.fromZIO(
      Ref.make(Map(seededSchemaId -> seededSchema)).map(store =>
        new SchemaRepository:
          def create(req: ProposeEntityTypeSchema): ZIO[ZConnectionPool, AppError, EntityTypeSchema] =
            val schema = EntityTypeSchema(
              id               = UUID.randomUUID(),
              domain           = req.domain,
              entityType       = req.entityType,
              schemaVersion    = 1,
              description      = req.description,
              fieldDefinitions = req.fieldDefinitions,
              mandatoryFields  = Nil,
              extractionPrompt = req.extractionPrompt,
              isActive         = true,
              changeDescription = req.changeDescription,
              createdAt        = Instant.now(),
            )
            store.update(_ + (schema.id -> schema)).as(schema)
          def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
            store.get.map(_.get(id))
          def findCurrent(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, Option[EntityTypeSchema]] =
            store.get.map(_.values.find(s => s.domain == domain && s.entityType == entityType && s.isActive))
          def listCurrent(domain: Option[String]): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
            store.get.map(_.values.filter(s => s.isActive && domain.forall(_ == s.domain)).toList)
          def findAll(domain: String, entityType: String): ZIO[ZConnectionPool, AppError, List[EntityTypeSchema]] =
            store.get.map(_.values.filter(s => s.domain == domain && s.entityType == entityType).toList)
          def deactivate(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
            store.get.flatMap: m =>
              m.get(id) match
                case None    => ZIO.succeed(false)
                case Some(s) => store.update(_ + (id -> s.copy(isActive = false))).as(true)
      )
    )

  // ── In-memory DocumentRepository ────────────────────────────────────────────
  private val documentRepoLayer: ZLayer[Any, Nothing, DocumentRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[UUID, Document]).map(store =>
        new DocumentRepository:
          def create(req: CreateDocument): ZIO[ZConnectionPool, AppError, Document] =
            val doc = Document(
              id           = UUID.randomUUID(),
              personId     = req.personId,
              householdId  = req.householdId,
              contentText  = req.contentText,
              sourceType   = req.sourceType,
              files        = req.files,
              supersedesIds = req.supersedesIds,
              createdAt    = Instant.now(),
            )
            store.update(_ + (doc.id -> doc)).as(doc)
          def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Document]] =
            store.get.map(_.get(id))
          def list(
              personId:    Option[UUID],
              householdId: Option[UUID],
              sourceType:  Option[String],
              limit:       Int,
              offset:      Int,
          ): ZIO[ZConnectionPool, AppError, List[Document]] =
            store.get.map(_.values
              .filter(d => personId.forall(p => d.personId.contains(p)))
              .filter(d => householdId.forall(h => d.householdId.contains(h)))
              .filter(d => sourceType.forall(_ == d.sourceType))
              .toList.sortBy(_.createdAt).reverse
              .drop(offset).take(limit))
          def count(
              personId:    Option[UUID],
              householdId: Option[UUID],
              sourceType:  Option[String],
          ): ZIO[ZConnectionPool, AppError, Long] =
            store.get.map(_.values
              .filter(d => personId.forall(p => d.personId.contains(p)))
              .filter(d => householdId.forall(h => d.householdId.contains(h)))
              .filter(d => sourceType.forall(_ == d.sourceType))
              .size.toLong)
      )
    )

  // ── In-memory FactRepository ─────────────────────────────────────────────────
  private val factRepoLayer: ZLayer[Any, Nothing, FactRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[UUID, Fact]).map(store =>
        new FactRepository:
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
            store.get.map(_.values.filter(_.schemaId == schemaId).toList.sortBy(_.createdAt).drop(offset).take(limit))
      )
    )

  private def startEmbeddedServer(): Unit =
    val runnable: Runnable = () =>
      try
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(
            Server
              .serve(minimalRoutes)
              .provide(
                Server.defaultWithPort(TestPort),
                personRepoLayer,
                PersonService.live,
                schemaRepoLayer,
                SchemaService.live,
                documentRepoLayer,
                DocumentService.live,
                factRepoLayer,
                FactService.live,
                ZConnectionPool.h2test.orDie,
              )
          ).getOrThrowFiberFailure()
        }
      catch
        case t: Throwable =>
          java.lang.System.err.println(s"[CucumberServer] Server thread crashed: ${t.getClass.getName}: ${t.getMessage}")
          t.printStackTrace(java.lang.System.err)
    val t = new Thread(runnable, "cucumber-test-server")
    t.setDaemon(true)
    t.start()

  if sys.env.get("TEST_BASE_URL").isEmpty && sys.props.get("TEST_BASE_URL").isEmpty then
    java.lang.System.setProperty("TEST_BASE_URL", s"http://localhost:$TestPort")
    startEmbeddedServer()
