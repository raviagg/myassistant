package com.myassistant.unit.services

import com.myassistant.db.repositories.{ReferenceRepository, RelationshipRepository}
import com.myassistant.domain.{Domain, KinshipAlias, Relationship, RelationType, SourceType, CreateRelationship}
import com.myassistant.errors.AppError
import com.myassistant.services.KinshipResolver
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

/** Unit tests for KinshipResolver BFS graph traversal logic using in-memory mocks. */
object KinshipResolverSpec extends ZIOSpecDefault:

  // ── In-memory mock RelationshipRepository ────────────────────────────────

  final class MockRelationshipRepository(store: Ref[Map[UUID, Relationship]]) extends RelationshipRepository:

    def create(req: CreateRelationship): ZIO[ZConnectionPool, AppError, Relationship] =
      val now = Instant.now()
      val rel = Relationship(UUID.randomUUID(), req.fromPersonId, req.toPersonId, req.relationType, now, now)
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

  // ── In-memory mock ReferenceRepository ───────────────────────────────────

  /** Holds a fixed list of KinshipAlias entries for lookup. */
  final class MockReferenceRepository(aliases: List[KinshipAlias]) extends ReferenceRepository:

    def listDomains: ZIO[ZConnectionPool, AppError, List[Domain]] =
      ZIO.succeed(Nil)

    def listSourceTypes: ZIO[ZConnectionPool, AppError, List[SourceType]] =
      ZIO.succeed(Nil)

    def createDomain(name: String, description: String): ZIO[ZConnectionPool, AppError, Domain] =
      ZIO.fail(AppError.InternalError(new UnsupportedOperationException("mock")))

    def createSourceType(name: String, description: String): ZIO[ZConnectionPool, AppError, SourceType] =
      ZIO.fail(AppError.InternalError(new UnsupportedOperationException("mock")))

    def listKinshipAliases(language: Option[String]): ZIO[ZConnectionPool, AppError, List[KinshipAlias]] =
      ZIO.succeed(language match
        case None       => aliases
        case Some(lang) => aliases.filter(_.language == lang)
      )

  // ── Fixed test persons ────────────────────────────────────────────────────
  //
  // Family graph (fromPerson IS relationType OF toPerson):
  //   Raj    --Father-->   Arjun
  //   Nirmala --Mother-->  Arjun
  //   Rajni  --Sister-->   Raj      (i.e. from Arjun's perspective Rajni is father's sister = bua)

  val arjunId  : UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
  val rajId    : UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
  val nirmalaId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
  val rajniId  : UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")

  // ── Seed aliases ──────────────────────────────────────────────────────────

  val buaAlias: KinshipAlias = KinshipAlias(
    id            = 1,
    relationChain = List("father", "sister"),
    language      = "hindi",
    alias         = "bua",
    description   = Some("father's sister"),
    createdAt     = Instant.now(),
  )

  // ── Layer factories ───────────────────────────────────────────────────────

  /** Build a RelationshipRepository pre-loaded with the family graph. */
  def familyRelRepoLayer: ZLayer[Any, Nothing, RelationshipRepository] =
    ZLayer.fromZIO:
      for
        store <- Ref.make(Map.empty[UUID, Relationship])
        repo   = new MockRelationshipRepository(store)
        now    = Instant.now()
        // Raj is Father of Arjun
        rajFather = Relationship(UUID.randomUUID(), rajId, arjunId, RelationType.Father, now, now)
        // Nirmala is Mother of Arjun
        nirMother = Relationship(UUID.randomUUID(), nirmalaId, arjunId, RelationType.Mother, now, now)
        // Rajni is Sister of Raj
        rajniSister = Relationship(UUID.randomUUID(), rajniId, rajId, RelationType.Sister, now, now)
        _  <- store.update(_ + (rajFather.id -> rajFather) + (nirMother.id -> nirMother) + (rajniSister.id -> rajniSister))
      yield repo

  def refRepoWithBuaLayer: ZLayer[Any, Nothing, ReferenceRepository] =
    ZLayer.succeed(new MockReferenceRepository(List(buaAlias)))

  def emptyRefRepoLayer: ZLayer[Any, Nothing, ReferenceRepository] =
    ZLayer.succeed(new MockReferenceRepository(Nil))

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("KinshipResolverSpec")(

      suite("resolveKinship")(

        test("returns chain [father, sister] with alias 'bua' for Arjun → Rajni path") {
          for
            resolver <- ZIO.service[KinshipResolver]
            result   <- resolver.resolve(arjunId, rajniId, "hindi")
          yield result match
            case None => assertTrue(false) // must find a path
            case Some(kr) =>
              assertTrue(kr.chain == List(RelationType.Father, RelationType.Sister)) &&
              assertTrue(kr.alias.contains("bua")) &&
              assertTrue(kr.description.contains("father"))
        }.provide(familyRelRepoLayer, refRepoWithBuaLayer, KinshipResolver.live, ZConnectionPool.h2test),

        test("returns None when no path exists between unrelated persons") {
          val strangerA = UUID.randomUUID()
          val strangerB = UUID.randomUUID()
          for
            resolver <- ZIO.service[KinshipResolver]
            result   <- resolver.resolve(strangerA, strangerB, "hindi")
          yield assertTrue(result.isEmpty)
        }.provide(familyRelRepoLayer, refRepoWithBuaLayer, KinshipResolver.live, ZConnectionPool.h2test),

        test("returns chain without alias when no alias registered for chain") {
          // Arjun → Nirmala: path is [mother] — no alias seeded for this chain
          for
            resolver <- ZIO.service[KinshipResolver]
            result   <- resolver.resolve(arjunId, nirmalaId, "hindi")
          yield result match
            case None => assertTrue(false) // must find a path
            case Some(kr) =>
              assertTrue(kr.alias.isEmpty) &&
              assertTrue(kr.description.nonEmpty)
        }.provide(familyRelRepoLayer, emptyRefRepoLayer, KinshipResolver.live, ZConnectionPool.h2test),

        test("returns None when fromPersonId == toPersonId") {
          for
            resolver <- ZIO.service[KinshipResolver]
            result   <- resolver.resolve(arjunId, arjunId, "hindi")
          yield assertTrue(result.isEmpty)
        }.provide(familyRelRepoLayer, refRepoWithBuaLayer, KinshipResolver.live, ZConnectionPool.h2test),

      ),

    )
