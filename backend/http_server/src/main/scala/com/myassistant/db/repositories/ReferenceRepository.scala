package com.myassistant.db.repositories

import com.myassistant.domain.{Domain, KinshipAlias, SourceType}
import com.myassistant.errors.AppError
import io.circe.parser as circeParser
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.util.UUID

/** Data-access interface for the `domain`, `source_type`, and `kinship_alias` reference tables. */
trait ReferenceRepository:
  /** Return all domains. */
  def listDomains: ZIO[ZConnectionPool, AppError, List[Domain]]

  /** Return all source types. */
  def listSourceTypes: ZIO[ZConnectionPool, AppError, List[SourceType]]

  /** Insert a new domain. */
  def createDomain(name: String, description: String): ZIO[ZConnectionPool, AppError, Domain]

  /** Insert a new source type. */
  def createSourceType(name: String, description: String): ZIO[ZConnectionPool, AppError, SourceType]

  /** Return kinship aliases, optionally filtered by language. */
  def listKinshipAliases(language: Option[String]): ZIO[ZConnectionPool, AppError, List[KinshipAlias]]

object ReferenceRepository:

  // ── Row types ─────────────────────────────────────────────────────────────
  private type DomainRow       = (String, String, String, java.sql.Timestamp)
  private type SourceTypeRow   = (String, String, String, java.sql.Timestamp)
  // id, relation_chain (JSON array text), language, alias, description, created_at
  private type KinshipAliasRow = (Int, String, String, String, Option[String], java.sql.Timestamp)

  // ── Row → domain ──────────────────────────────────────────────────────────
  private def rowToDomain(row: DomainRow): Domain =
    val (id, name, description, createdAt) = row
    Domain(UUID.fromString(id), name, description, createdAt.toInstant)

  private def rowToSourceType(row: SourceTypeRow): SourceType =
    val (id, name, description, createdAt) = row
    SourceType(UUID.fromString(id), name, description, createdAt.toInstant)

  private def rowToKinshipAlias(row: KinshipAliasRow): KinshipAlias =
    val (id, chainJson, language, alias, description, createdAt) = row
    val chain = circeParser.parse(chainJson)
      .toOption
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.asString))
      .getOrElse(Nil)
    KinshipAlias(id, chain, language, alias, description, createdAt.toInstant)

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation — SQL queries against PostgreSQL. */
  final class Live extends ReferenceRepository:

    /** Return all domains ordered by name. */
    def listDomains: ZIO[ZConnectionPool, AppError, List[Domain]] =
      transaction {
        sql"""
          SELECT id::text, name, description, created_at
          FROM domain
          ORDER BY name
        """.query[DomainRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToDomain))

    /** Return all source types ordered by name. */
    def listSourceTypes: ZIO[ZConnectionPool, AppError, List[SourceType]] =
      transaction {
        sql"""
          SELECT id::text, name, description, created_at
          FROM source_type
          ORDER BY name
        """.query[SourceTypeRow].selectAll
      }.mapError(mapSqlError)
        .map(_.toList.map(rowToSourceType))

    /** Insert a new domain and return the persisted record. */
    def createDomain(name: String, description: String): ZIO[ZConnectionPool, AppError, Domain] =
      transaction {
        sql"""
          INSERT INTO domain(name, description)
          VALUES ($name, $description)
          RETURNING id::text, name, description, created_at
        """.query[DomainRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT domain returned no row"))))
        .map(rowToDomain)

    /** Insert a new source type and return the persisted record. */
    def createSourceType(name: String, description: String): ZIO[ZConnectionPool, AppError, SourceType] =
      transaction {
        sql"""
          INSERT INTO source_type(name, description)
          VALUES ($name, $description)
          RETURNING id::text, name, description, created_at
        """.query[SourceTypeRow].selectOne
      }.mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("INSERT source_type returned no row"))))
        .map(rowToSourceType)

    /** Return kinship aliases ordered by id, optionally filtered by language. */
    def listKinshipAliases(language: Option[String]): ZIO[ZConnectionPool, AppError, List[KinshipAlias]] =
      language match
        case None =>
          transaction {
            sql"""
              SELECT id, array_to_json(relation_chain)::text, language, alias, description, created_at
              FROM kinship_alias
              ORDER BY id
            """.query[KinshipAliasRow].selectAll
          }.mapError(mapSqlError)
            .map(_.toList.map(rowToKinshipAlias))
        case Some(lang) =>
          transaction {
            sql"""
              SELECT id, array_to_json(relation_chain)::text, language, alias, description, created_at
              FROM kinship_alias
              WHERE language = $lang
              ORDER BY id
            """.query[KinshipAliasRow].selectAll
          }.mapError(mapSqlError)
            .map(_.toList.map(rowToKinshipAlias))

  /** ZLayer providing the live ReferenceRepository. */
  val live: ZLayer[Any, Nothing, ReferenceRepository] =
    ZLayer.succeed(new Live)
