package com.myassistant.unit.routes

import com.myassistant.api.routes.PersonRoutes
import com.myassistant.db.repositories.PersonRepository
import com.myassistant.domain.{CreatePerson, Gender, Person, UpdatePerson}
import com.myassistant.errors.AppError
import com.myassistant.services.PersonService
import zio.*
import zio.http.*
import zio.jdbc.ZConnectionPool
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object PersonRoutesSpec extends ZIOSpecDefault:

  final class MockPersonRepository(store: Ref[Map[UUID, Person]]) extends PersonRepository:

    def create(req: CreatePerson): ZIO[ZConnectionPool, AppError, Person] =
      if req.fullName.isBlank then
        ZIO.fail(AppError.ValidationError("fullName must not be blank"))
      else
        val now = Instant.now()
        val p = Person(
          id             = UUID.randomUUID(),
          fullName       = req.fullName,
          gender         = req.gender,
          dateOfBirth    = req.dateOfBirth,
          preferredName  = req.preferredName,
          userIdentifier = req.userIdentifier,
          createdAt      = now,
          updatedAt      = now,
        )
        store.update(_ + (p.id -> p)).as(p)

    def findById(id: UUID): ZIO[ZConnectionPool, AppError, Option[Person]] =
      store.get.map(_.get(id))

    def findByUserIdentifier(identifier: String): ZIO[ZConnectionPool, AppError, Option[Person]] =
      store.get.map(_.values.find(_.userIdentifier.contains(identifier)))

    def search(
        name:            Option[String],
        gender:          Option[String],
        dateOfBirth:     Option[java.time.LocalDate],
        dateOfBirthFrom: Option[java.time.LocalDate],
        dateOfBirthTo:   Option[java.time.LocalDate],
        householdId:     Option[UUID],
        limit:           Int,
        offset:          Int,
    ): ZIO[ZConnectionPool, AppError, List[Person]] =
      store.get.map(_.values.toList.sortBy(_.fullName))

    def update(id: UUID, patch: UpdatePerson): ZIO[ZConnectionPool, AppError, Option[Person]] =
      store.get.flatMap: m =>
        m.get(id) match
          case None => ZIO.succeed(None)
          case Some(existing) =>
            val updated = existing.copy(
              fullName       = patch.fullName.getOrElse(existing.fullName),
              gender         = patch.gender.getOrElse(existing.gender),
              dateOfBirth    = patch.dateOfBirth.orElse(existing.dateOfBirth),
              preferredName  = patch.preferredName.orElse(existing.preferredName),
              userIdentifier = patch.userIdentifier.orElse(existing.userIdentifier),
              updatedAt      = Instant.now(),
            )
            store.update(_ + (id -> updated)).as(Some(updated))

    def delete(id: UUID): ZIO[ZConnectionPool, AppError, Boolean] =
      store.get.flatMap: m =>
        if m.contains(id) then store.update(_ - id).as(true)
        else ZIO.succeed(false)

  val mockRepoLayer: ZLayer[Any, Nothing, PersonRepository] =
    ZLayer.fromZIO(Ref.make(Map.empty[UUID, Person]).map(new MockPersonRepository(_)))

  private def withFreshRoutes[E](spec: Spec[PersonService & ZConnectionPool, E]): Spec[Any, E] =
    spec.provide(mockRepoLayer, PersonService.live, ZConnectionPool.h2test.orDie)

  private def getUrl(path: String): URL =
    URL.decode(s"http://localhost$path").fold(_ => URL.empty, identity)

  def spec: Spec[Any, Any] =
    suite("PersonRoutesSpec")(

      withFreshRoutes(
        suite("GET /api/v1/persons")(

          test("returns 200 with items array when no persons exist") {
            for
              response <- PersonRoutes.routes.runZIO(
                            Request.get(getUrl("/api/v1/persons"))
                          )
              body     <- response.body.asString
            yield assertTrue(response.status == Status.Ok) &&
                  assertTrue(body.contains("items"))
          },

        )
      ),

      withFreshRoutes(
        suite("POST /api/v1/persons")(

          test("returns 201 with created person") {
            val req = Request
              .post(
                getUrl("/api/v1/persons"),
                Body.fromString("""{"fullName":"Raj Sharma","gender":"male"}"""),
              )
              .addHeader(Header.ContentType(MediaType.application.json))
            for
              response <- PersonRoutes.routes.runZIO(req)
              body     <- response.body.asString
            yield assertTrue(response.status == Status.Created) &&
                  assertTrue(body.contains("Raj Sharma"))
          },

          test("returns 400 for malformed JSON") {
            val req = Request
              .post(
                getUrl("/api/v1/persons"),
                Body.fromString("not-json"),
              )
              .addHeader(Header.ContentType(MediaType.application.json))
            for
              response <- PersonRoutes.routes.runZIO(req)
            yield assertTrue(response.status == Status.BadRequest)
          },

          test("returns 422 for unknown gender") {
            val req = Request
              .post(
                getUrl("/api/v1/persons"),
                Body.fromString("""{"fullName":"X","gender":"alien"}"""),
              )
              .addHeader(Header.ContentType(MediaType.application.json))
            for
              response <- PersonRoutes.routes.runZIO(req)
            yield assertTrue(response.status == Status.UnprocessableEntity)
          },

        )
      ),

      withFreshRoutes(
        suite("GET /api/v1/persons/:id")(

          test("returns 200 when person exists") {
            val createReq = Request
              .post(
                getUrl("/api/v1/persons"),
                Body.fromString("""{"fullName":"Priya Sharma","gender":"female"}"""),
              )
              .addHeader(Header.ContentType(MediaType.application.json))
            for
              created  <- PersonRoutes.routes.runZIO(createReq)
              body     <- created.body.asString
              id       <- ZIO.fromOption(
                            """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1))
                          ).orElseFail(new Exception("no id in create response"))
              response <- PersonRoutes.routes.runZIO(
                            Request.get(getUrl(s"/api/v1/persons/$id"))
                          )
              getBody  <- response.body.asString
            yield assertTrue(response.status == Status.Ok) &&
                  assertTrue(getBody.contains("Priya Sharma"))
          },

          test("returns 404 when person does not exist") {
            val missing = UUID.randomUUID()
            for
              response <- PersonRoutes.routes.runZIO(
                            Request.get(getUrl(s"/api/v1/persons/$missing"))
                          )
            yield assertTrue(response.status == Status.NotFound)
          },

          test("returns 400 for invalid UUID") {
            for
              response <- PersonRoutes.routes.runZIO(
                            Request.get(getUrl("/api/v1/persons/not-a-uuid"))
                          )
            yield assertTrue(response.status == Status.BadRequest)
          },

        )
      ),

      withFreshRoutes(
        suite("DELETE /api/v1/persons/:id")(

          test("returns 204 when person exists") {
            val createReq = Request
              .post(
                getUrl("/api/v1/persons"),
                Body.fromString("""{"fullName":"ToDelete","gender":"male"}"""),
              )
              .addHeader(Header.ContentType(MediaType.application.json))
            for
              created  <- PersonRoutes.routes.runZIO(createReq)
              body     <- created.body.asString
              id       <- ZIO.fromOption(
                            """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1))
                          ).orElseFail(new Exception("no id in create response"))
              response <- PersonRoutes.routes.runZIO(
                            Request.delete(getUrl(s"/api/v1/persons/$id"))
                          )
            yield assertTrue(response.status == Status.NoContent)
          },

        )
      ),

    )
