package com.myassistant.unit.services

import com.myassistant.db.repositories.AuditRepository
import com.myassistant.domain.{AuditLog, InteractionStatus}
import com.myassistant.errors.AppError
import com.myassistant.services.AuditService
import io.circe.Json
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object AuditServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock AuditRepository ───────────────────────────

  final class MockAuditRepository(store: Ref[Map[UUID, AuditLog]]) extends AuditRepository:

    def create(entry: AuditLog): ZIO[ZConnectionPool, AppError, AuditLog] =
      store.update(_ + (entry.id -> entry)).as(entry)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[AuditLog]] =
      store.get.map(_.get(id))

    def listByPerson(personId: UUID, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      store.get.map:
        _.values.filter(_.personId.contains(personId)).toList
          .sortBy(_.createdAt).reverse
          .slice(offset, offset + limit)

    def listByJobType(jobType: String, limit: Int, offset: Int): ZIO[ZConnectionPool, AppError, List[AuditLog]] =
      store.get.map:
        _.values.filter(_.jobType.contains(jobType)).toList
          .sortBy(_.createdAt).reverse
          .slice(offset, offset + limit)

  // ── Layer factory ─────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, AuditRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, AuditLog]).map(new MockAuditRepository(_)))

  private def personEntry(personId: UUID): AuditLog =
    AuditLog(
      id        = UUID.randomUUID(),
      personId  = Some(personId),
      jobType   = None,
      message   = "Hello",
      response  = Some("Hi"),
      toolCalls = Json.arr(),
      status    = InteractionStatus.Success,
      error     = None,
      createdAt = Instant.now(),
    )

  private def jobEntry(jobType: String): AuditLog =
    AuditLog(
      id        = UUID.randomUUID(),
      personId  = None,
      jobType   = Some(jobType),
      message   = "poll result",
      response  = None,
      toolCalls = Json.arr(),
      status    = InteractionStatus.Success,
      error     = None,
      createdAt = Instant.now(),
    )

  private def withFreshService[E](spec: Spec[AuditService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, AuditService.live, ZConnectionPool.h2test.orDie)

  // ── Tests ─────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("AuditServiceSpec")(

      withFreshService(
        suite("log validation")(

          test("fails when both personId and jobType are set") {
            val entry = AuditLog(
              id        = UUID.randomUUID(),
              personId  = Some(UUID.randomUUID()),
              jobType   = Some("plaid_poll"),
              message   = "bad entry",
              response  = None,
              toolCalls = Json.arr(),
              status    = InteractionStatus.Failed,
              error     = None,
              createdAt = Instant.now(),
            )
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.log(entry).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("fails when neither personId nor jobType is set") {
            val entry = AuditLog(
              id        = UUID.randomUUID(),
              personId  = None,
              jobType   = None,
              message   = "bad entry",
              response  = None,
              toolCalls = Json.arr(),
              status    = InteractionStatus.Failed,
              error     = None,
              createdAt = Instant.now(),
            )
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.log(entry).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("succeeds for a person interaction") {
            val personId = UUID.randomUUID()
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.log(personEntry(personId))
            yield assertTrue(result.personId.contains(personId)) &&
                  assertTrue(result.jobType.isEmpty)
          },

          test("succeeds for a job interaction") {
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.log(jobEntry("plaid_poll"))
            yield assertTrue(result.jobType.contains("plaid_poll")) &&
                  assertTrue(result.personId.isEmpty)
          },

        )
      ),

      withFreshService(
        suite("getEntry")(

          test("returns NotFound when entry does not exist") {
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.getEntry(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns entry when it exists") {
            val personId = UUID.randomUUID()
            val entry    = personEntry(personId)
            for
              svc   <- ZIO.service[AuditService]
              _     <- svc.log(entry)
              found <- svc.getEntry(entry.id)
            yield assertTrue(found.id == entry.id)
          },

        )
      ),

      withFreshService(
        suite("listByPerson")(

          test("returns empty list for a person with no entries") {
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.listByPerson(UUID.randomUUID(), 10, 0)
            yield assertTrue(result.isEmpty)
          },

          test("returns all entries for a person") {
            val personId = UUID.randomUUID()
            for
              svc  <- ZIO.service[AuditService]
              _    <- svc.log(personEntry(personId))
              _    <- svc.log(personEntry(personId))
              list <- svc.listByPerson(personId, 10, 0)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.forall(_.personId.contains(personId)))
          },

        )
      ),

      withFreshService(
        suite("listByJobType")(

          test("returns empty list for an unknown job type") {
            for
              svc    <- ZIO.service[AuditService]
              result <- svc.listByJobType("unknown_job", 10, 0)
            yield assertTrue(result.isEmpty)
          },

          test("returns all entries for a job type") {
            for
              svc  <- ZIO.service[AuditService]
              _    <- svc.log(jobEntry("plaid_poll"))
              _    <- svc.log(jobEntry("plaid_poll"))
              _    <- svc.log(jobEntry("gmail_poll"))
              list <- svc.listByJobType("plaid_poll", 10, 0)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.forall(_.jobType.contains("plaid_poll")))
          },

        )
      ),

    )
