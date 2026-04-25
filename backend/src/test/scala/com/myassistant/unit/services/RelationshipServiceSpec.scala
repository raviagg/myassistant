package com.myassistant.unit.services

import com.myassistant.db.repositories.RelationshipRepository
import com.myassistant.domain.{CreateRelationship, Relationship, RelationType}
import com.myassistant.errors.AppError
import com.myassistant.services.RelationshipService
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

/** Unit tests for RelationshipService business logic using an in-memory mock RelationshipRepository. */
object RelationshipServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock RelationshipRepository ────────────────────────────────

  final class MockRelationshipRepository(store: Ref[Map[UUID, Relationship]]) extends RelationshipRepository:

    def create(req: CreateRelationship): ZIO[ZConnectionPool, AppError, Relationship] =
      val now = Instant.now()
      val rel = Relationship(
        id           = UUID.randomUUID(),
        fromPersonId = req.fromPersonId,
        toPersonId   = req.toPersonId,
        relationType = req.relationType,
        createdAt    = now,
        updatedAt    = now,
      )
      store.update(_ + (rel.id -> rel)).as(rel)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Relationship]] =
      store.get.map(_.get(id))

    def findByPerson(personId: UUID): ZIO[ZConnectionPool, AppError, List[Relationship]] =
      store.get.map:
        _.values.filter(r => r.fromPersonId == personId || r.toPersonId == personId).toList

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      store.get.flatMap: m =>
        if m.contains(id) then store.update(_ - id).as(true)
        else ZIO.succeed(false)

  // ── Layer factory ─────────────────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, RelationshipRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, Relationship]).map(new MockRelationshipRepository(_)))

  // ── Tests ─────────────────────────────────────────────────────────────────

  /** Provide a fresh in-memory RelationshipService to each sub-suite independently. */
  private def withFreshService[E](spec: Spec[RelationshipService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, RelationshipService.live, ZConnectionPool.h2test)

  def spec: Spec[Any, Any] =
    suite("RelationshipServiceSpec")(

      withFreshService(
        suite("createRelationship")(

          test("fails with ValidationError when fromPersonId == toPersonId") {
            val selfId = UUID.randomUUID()
            val req = CreateRelationship(
              fromPersonId = selfId,
              toPersonId   = selfId,
              relationType = RelationType.Father,
            )
            for
              svc    <- ZIO.service[RelationshipService]
              result <- svc.createRelationship(req).exit
            yield assert(result)(fails(isSubtype[AppError.ValidationError](anything)))
          },

          test("stores a relationship and returns it with a generated id") {
            val from = UUID.randomUUID()
            val to   = UUID.randomUUID()
            val req  = CreateRelationship(
              fromPersonId = from,
              toPersonId   = to,
              relationType = RelationType.Father,
            )
            for
              svc    <- ZIO.service[RelationshipService]
              result <- svc.createRelationship(req)
            yield assertTrue(result.id != null) &&
                  assertTrue(result.fromPersonId == from) &&
                  assertTrue(result.toPersonId == to) &&
                  assertTrue(result.relationType == RelationType.Father)
          },

        )
      ),

      withFreshService(
        suite("getRelationship")(

          test("returns NotFound when relationship does not exist") {
            for
              svc    <- ZIO.service[RelationshipService]
              result <- svc.getRelationship(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("returns the relationship when it exists") {
            val from = UUID.randomUUID()
            val to   = UUID.randomUUID()
            val req  = CreateRelationship(from, to, RelationType.Mother)
            for
              svc     <- ZIO.service[RelationshipService]
              created <- svc.createRelationship(req)
              found   <- svc.getRelationship(created.id)
            yield assertTrue(found.id == created.id) &&
                  assertTrue(found.relationType == RelationType.Mother)
          },

        )
      ),

      withFreshService(
        suite("listRelationships")(

          test("returns empty list for a person with no relationships") {
            for
              svc    <- ZIO.service[RelationshipService]
              result <- svc.listRelationships(UUID.randomUUID())
            yield assertTrue(result.isEmpty)
          },

          test("returns all relationships for a person") {
            val personId = UUID.randomUUID()
            val otherId1 = UUID.randomUUID()
            val otherId2 = UUID.randomUUID()
            for
              svc  <- ZIO.service[RelationshipService]
              _    <- svc.createRelationship(CreateRelationship(personId, otherId1, RelationType.Son))
              _    <- svc.createRelationship(CreateRelationship(personId, otherId2, RelationType.Daughter))
              list <- svc.listRelationships(personId)
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.forall(r => r.fromPersonId == personId || r.toPersonId == personId))
          },

        )
      ),

      withFreshService(
        suite("deleteRelationship")(

          test("returns NotFound when relationship does not exist") {
            for
              svc    <- ZIO.service[RelationshipService]
              result <- svc.deleteRelationship(UUID.randomUUID()).exit
            yield assert(result)(fails(isSubtype[AppError.NotFound](anything)))
          },

          test("removes the relationship successfully") {
            val from = UUID.randomUUID()
            val to   = UUID.randomUUID()
            for
              svc     <- ZIO.service[RelationshipService]
              created <- svc.createRelationship(CreateRelationship(from, to, RelationType.Brother))
              _       <- svc.deleteRelationship(created.id)
              found   <- svc.getRelationship(created.id).exit
            yield assert(found)(fails(isSubtype[AppError.NotFound](anything)))
          },

        )
      ),

    )
