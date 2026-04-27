package com.myassistant.unit.services

import com.myassistant.db.repositories.ReferenceRepository
import com.myassistant.domain.{Domain, KinshipAlias, SourceType}
import com.myassistant.errors.AppError
import com.myassistant.services.ReferenceService
import zio.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object ReferenceServiceSpec extends ZIOSpecDefault:

  // ── In-memory mock ReferenceRepository ───────────────────────

  final class MockReferenceRepository(
      domains:    Ref[Map[String, Domain]],
      sources:    Ref[Map[String, SourceType]],
      aliases:    Ref[List[KinshipAlias]],
      idCounter:  Ref[Int],
  ) extends ReferenceRepository:

    private def now = Instant.now()

    def listDomains: ZIO[ZConnectionPool, AppError, List[Domain]] =
      domains.get.map(_.values.toList.sortBy(_.name))

    def listSourceTypes: ZIO[ZConnectionPool, AppError, List[SourceType]] =
      sources.get.map(_.values.toList.sortBy(_.name))

    def createDomain(name: String, description: String): ZIO[ZConnectionPool, AppError, Domain] =
      val d = Domain(name, description, now)
      domains.update(_ + (name -> d)).as(d)

    def createSourceType(name: String, description: String): ZIO[ZConnectionPool, AppError, SourceType] =
      val st = SourceType(name, description, now)
      sources.update(_ + (name -> st)).as(st)

    def listKinshipAliases(language: Option[String]): ZIO[ZConnectionPool, AppError, List[KinshipAlias]] =
      aliases.get.map: list =>
        language.fold(list)(lang => list.filter(_.language == lang))

  // ── Layer factory ─────────────────────────────────────────────

  val mockRepoLayer: ZLayer[Any, Nothing, ReferenceRepository] =
    ZLayer.fromZIO:
      for
        d <- Ref.make(Map.empty[String, Domain])
        s <- Ref.make(Map.empty[String, SourceType])
        a <- Ref.make(List(
               KinshipAlias(1, List("father", "sister"), "hindi",   "bua",  Some("Father's sister"), Instant.now()),
               KinshipAlias(2, List("mother", "brother"), "hindi",  "mama", Some("Mother's brother"), Instant.now()),
               KinshipAlias(3, List("father", "sister"), "english", "aunt", Some("Father's sister"), Instant.now()),
             ))
        c <- Ref.make(4)
      yield new MockReferenceRepository(d, s, a, c)

  private def withFreshService[E](spec: Spec[ReferenceService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, ReferenceService.live, ZConnectionPool.h2test.orDie)

  // ── Tests ─────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("ReferenceServiceSpec")(

      withFreshService(
        suite("listDomains")(

          test("returns empty list when no domains exist") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.listDomains
            yield assertTrue(result.isEmpty)
          },

          test("returns all created domains") {
            for
              svc  <- ZIO.service[ReferenceService]
              _    <- svc.createDomain("health", "Health-related facts")
              _    <- svc.createDomain("finance", "Finance-related facts")
              list <- svc.listDomains
            yield assertTrue(list.size == 2) &&
                  assertTrue(list.map(_.name).contains("health")) &&
                  assertTrue(list.map(_.name).contains("finance"))
          },

        )
      ),

      withFreshService(
        suite("createDomain")(

          test("creates domain and returns it") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.createDomain("todo", "Task management")
            yield assertTrue(result.name == "todo") &&
                  assertTrue(result.description == "Task management")
          },

        )
      ),

      withFreshService(
        suite("listSourceTypes")(

          test("returns empty list when no source types exist") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.listSourceTypes
            yield assertTrue(result.isEmpty)
          },

          test("returns created source types") {
            for
              svc  <- ZIO.service[ReferenceService]
              _    <- svc.createSourceType("user_input", "Typed by user")
              list <- svc.listSourceTypes
            yield assertTrue(list.size == 1) &&
                  assertTrue(list.head.name == "user_input")
          },

        )
      ),

      withFreshService(
        suite("createSourceType")(

          test("creates source type and returns it") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.createSourceType("plaid_poll", "Plaid banking API")
            yield assertTrue(result.name == "plaid_poll") &&
                  assertTrue(result.description == "Plaid banking API")
          },

        )
      ),

      withFreshService(
        suite("listKinshipAliases")(

          test("returns all aliases when language filter is absent") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.listKinshipAliases(None)
            yield assertTrue(result.size == 3)
          },

          test("filters aliases by language") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.listKinshipAliases(Some("hindi"))
            yield assertTrue(result.size == 2) &&
                  assertTrue(result.forall(_.language == "hindi"))
          },

          test("returns empty list for an unknown language") {
            for
              svc    <- ZIO.service[ReferenceService]
              result <- svc.listKinshipAliases(Some("klingon"))
            yield assertTrue(result.isEmpty)
          },

        )
      ),

    )
