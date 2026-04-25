package com.myassistant.services

import com.myassistant.db.repositories.ReferenceRepository
import com.myassistant.domain.{Domain, KinshipAlias, SourceType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

/** Business-logic layer for reference data (domains, source types, and kinship aliases). */
trait ReferenceService:
  /** Return all life domains. */
  def listDomains: ZIO[ZConnectionPool, AppError, List[Domain]]

  /** Return all data source types. */
  def listSourceTypes: ZIO[ZConnectionPool, AppError, List[SourceType]]

  /** Add a new domain to the governed vocabulary. */
  def createDomain(name: String, description: String): ZIO[ZConnectionPool, AppError, Domain]

  /** Add a new source type to the governed vocabulary. */
  def createSourceType(name: String, description: String): ZIO[ZConnectionPool, AppError, SourceType]

  /** Return kinship aliases, optionally filtered by language. */
  def listKinshipAliases(language: Option[String]): ZIO[ZConnectionPool, AppError, List[KinshipAlias]]

object ReferenceService:

  /** Live implementation backed by ReferenceRepository. */
  final class Live(repo: ReferenceRepository) extends ReferenceService:

    /** Return all life domains ordered by name. */
    def listDomains: ZIO[ZConnectionPool, AppError, List[Domain]] =
      repo.listDomains

    /** Return all data source types ordered by name. */
    def listSourceTypes: ZIO[ZConnectionPool, AppError, List[SourceType]] =
      repo.listSourceTypes

    /** Insert a new domain into the governed vocabulary; passes through Conflict on duplicate name. */
    def createDomain(name: String, description: String): ZIO[ZConnectionPool, AppError, Domain] =
      repo.createDomain(name, description)

    /** Insert a new source type into the governed vocabulary; passes through Conflict on duplicate name. */
    def createSourceType(name: String, description: String): ZIO[ZConnectionPool, AppError, SourceType] =
      repo.createSourceType(name, description)

    /** Return all kinship aliases, optionally filtered by language. */
    def listKinshipAliases(language: Option[String]): ZIO[ZConnectionPool, AppError, List[KinshipAlias]] =
      repo.listKinshipAliases(language)

  /** ZLayer providing the live ReferenceService. */
  val live: ZLayer[ReferenceRepository, Nothing, ReferenceService] =
    ZLayer.fromFunction(new Live(_))
