package com.myassistant.services

import com.myassistant.db.repositories.FactRepository
import com.myassistant.domain.{CreateFact, CurrentFact, Fact}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

trait FactService:
  def createFact(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact]
  def getEntityHistory(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]]
  def getCurrentFact(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, CurrentFact]
  def listCurrentFacts(
      personId:    Option[UUID],
      householdId: Option[UUID],
      domainId:    Option[UUID],
      entityType:  Option[String],
      limit:       Int,
      offset:      Int,
  ): ZIO[ZConnectionPool, AppError, (List[CurrentFact], Long)]
  def searchCurrentFacts(
      embedding:           List[Double],
      personId:            Option[UUID],
      householdId:         Option[UUID],
      domainId:            Option[UUID],
      entityType:          Option[String],
      limit:               Int,
      similarityThreshold: Double,
  ): ZIO[ZConnectionPool, AppError, List[(CurrentFact, Double)]]

object FactService:

  final class Live(repo: FactRepository) extends FactService:

    def createFact(req: CreateFact): ZIO[ZConnectionPool, AppError, Fact] =
      repo.create(req)

    def getEntityHistory(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, List[Fact]] =
      repo.findByEntityInstance(entityInstanceId).flatMap:
        case Nil  => ZIO.fail(AppError.NotFound("entity_instance", entityInstanceId.toString))
        case rows => ZIO.succeed(rows)

    def getCurrentFact(entityInstanceId: UUID): ZIO[ZConnectionPool, AppError, CurrentFact] =
      repo.findCurrentByEntityInstance(entityInstanceId).flatMap:
        case Some(cf) => ZIO.succeed(cf)
        case None     => ZIO.fail(AppError.NotFound("current_fact", entityInstanceId.toString))

    def listCurrentFacts(
        personId:    Option[UUID],
        householdId: Option[UUID],
        domainId:    Option[UUID],
        entityType:  Option[String],
        limit:       Int,
        offset:      Int,
    ): ZIO[ZConnectionPool, AppError, (List[CurrentFact], Long)] =
      for
        items <- repo.listCurrent(personId, householdId, domainId, entityType, limit, offset)
        total <- repo.countCurrent(personId, householdId, domainId, entityType)
      yield (items, total)

    def searchCurrentFacts(
        embedding:           List[Double],
        personId:            Option[UUID],
        householdId:         Option[UUID],
        domainId:            Option[UUID],
        entityType:          Option[String],
        limit:               Int,
        similarityThreshold: Double,
    ): ZIO[ZConnectionPool, AppError, List[(CurrentFact, Double)]] =
      repo.searchCurrentBySimilarity(embedding, personId, householdId, domainId, entityType, limit, similarityThreshold)

  val live: ZLayer[FactRepository, Nothing, FactService] =
    ZLayer.fromFunction(new Live(_))
