package com.myassistant.e2e

import com.myassistant.api.routes.PersonRoutes
import com.myassistant.db.repositories.PersonRepository
import com.myassistant.domain.{CreatePerson, Gender, Person, UpdatePerson}
import com.myassistant.errors.AppError
import com.myassistant.services.PersonService
import zio.*
import zio.http.*
import zio.jdbc.ZConnectionPool

import java.time.Instant
import java.util.UUID

object CucumberServer:

  val TestPort = 8181

  private val minimalRoutes: Routes[PersonService & ZConnectionPool, Nothing] =
    Routes(
      Method.GET / "health" -> handler(Response.json("""{"status":"ok","version":"0.1.0"}""")),
    ) ++ PersonRoutes.routes

  private val inMemoryRepoLayer: ZLayer[Any, Nothing, PersonRepository] =
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

  private def startEmbeddedServer(): Unit =
    val t = new Thread(
      () =>
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(
            Server
              .serve(minimalRoutes)
              .provide(
                Server.defaultWithPort(TestPort),
                inMemoryRepoLayer,
                PersonService.live,
                ZConnectionPool.h2test.orDie,
              )
          ).getOrThrowFiberFailure()
        },
      "cucumber-test-server",
    )
    t.setDaemon(true)
    t.start()
    Thread.sleep(500)

  if sys.env.get("TEST_BASE_URL").isEmpty && sys.props.get("TEST_BASE_URL").isEmpty then
    java.lang.System.setProperty("TEST_BASE_URL", s"http://localhost:$TestPort")
    startEmbeddedServer()
