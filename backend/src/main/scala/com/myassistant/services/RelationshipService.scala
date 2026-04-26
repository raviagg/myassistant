package com.myassistant.services

import com.myassistant.db.repositories.RelationshipRepository
import com.myassistant.domain.{Relationship, CreateRelationship, RelationType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Business-logic layer for managing depth-1 relationships between persons. */
trait RelationshipService:
  /** Create a new relationship, checking for self-reference and duplicates. */
  def createRelationship(req: CreateRelationship): ZIO[ZConnectionPool, AppError, Relationship]

  /** Retrieve a relationship by id; fails with NotFound if absent. */
  def getRelationship(id: UUID): ZIO[ZConnectionPool, AppError, Relationship]

  /** Return all depth-1 relationships involving a person. */
  def listRelationships(personId: UUID): ZIO[ZConnectionPool, AppError, List[Relationship]]

  /** Update the relation type of an existing relationship by id. */
  def updateRelationship(id: UUID, newType: RelationType): ZIO[ZConnectionPool, AppError, Relationship]

  /** Delete a relationship by id. */
  def deleteRelationship(id: UUID): ZIO[ZConnectionPool, AppError, Unit]

object RelationshipService:

  /** Live implementation backed by RelationshipRepository. */
  final class Live(repo: RelationshipRepository) extends RelationshipService:

    /** Create a new relationship; rejects self-relationships before delegating to the repository. */
    def createRelationship(req: CreateRelationship): ZIO[ZConnectionPool, AppError, Relationship] =
      if req.fromPersonId == req.toPersonId then
        ZIO.fail(AppError.ValidationError("self-relationship not allowed"))
      else
        repo.create(req)

    /** Retrieve a relationship by primary key; fails with NotFound if absent. */
    def getRelationship(id: UUID): ZIO[ZConnectionPool, AppError, Relationship] =
      repo.findById(id).flatMap:
        case Some(r) => ZIO.succeed(r)
        case None    => ZIO.fail(AppError.NotFound("relationship", id.toString))

    /** Return all depth-1 relationships where personId appears on either side. */
    def listRelationships(personId: UUID): ZIO[ZConnectionPool, AppError, List[Relationship]] =
      repo.findByPerson(personId)

    /** Update the relation type by deleting and re-inserting with the new type.
     *
     *  RelationshipRepository has no UPDATE method; the workaround is a sequential
     *  delete + re-insert.  There is a brief window of inconsistency between the
     *  two operations, which is acceptable for depth-1 relationship metadata.
     */
    def updateRelationship(id: UUID, newType: RelationType): ZIO[ZConnectionPool, AppError, Relationship] =
      for
        existing <- getRelationship(id)
        _        <- repo.delete(id)
        updated  <- repo.create(CreateRelationship(
                      fromPersonId = existing.fromPersonId,
                      toPersonId   = existing.toPersonId,
                      relationType = newType,
                    ))
      yield updated

    /** Delete a relationship by id; fails with NotFound if no record matched. */
    def deleteRelationship(id: UUID): ZIO[ZConnectionPool, AppError, Unit] =
      repo.delete(id).flatMap:
        case true  => ZIO.unit
        case false => ZIO.fail(AppError.NotFound("relationship", id.toString))

  /** ZLayer providing the live RelationshipService. */
  val live: ZLayer[RelationshipRepository, Nothing, RelationshipService] =
    ZLayer.fromFunction(new Live(_))
