package com.myassistant.db.repositories

import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

final case class PlaidConnectionRow(
    id:                 UUID,
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    accessToken:        Option[String],
    createdAt:          Instant,
    updatedAt:          Instant,
)


trait PlaidSyncRepository:

  def upsertConnection(
      sourceConnectionId: UUID,
      plaidItemId:        String,
      institutionName:    String,
      cursor:             Option[String],
      accessToken:        Option[String],
  ): ZIO[ZConnectionPool, AppError, PlaidConnectionRow]

  def listBySourceConnectionId(
      sourceConnectionId: UUID,
  ): ZIO[ZConnectionPool, AppError, List[PlaidConnectionRow]]

  def deleteConnection(
      sourceConnectionId: UUID,
      itemId:             UUID,
  ): ZIO[ZConnectionPool, AppError, Boolean]

object PlaidSyncRepository:

  // id, source_connection_id, plaid_item_id, institution_name,
  // cursor, access_token, created_at, updated_at
  private type ConnRow =
    (String, String, String, String, Option[String], Option[String],
     java.sql.Timestamp, java.sql.Timestamp)

  private val connCols = SqlFragment(
    """id::text, source_connection_id::text, plaid_item_id, institution_name,
       cursor, access_token, created_at, updated_at"""
  )

  private def rowToConn(row: ConnRow): PlaidConnectionRow =
    val (id, scid, itemId, instName, cursor, accessToken, createdAt, updatedAt) = row
    PlaidConnectionRow(
      id                 = UUID.fromString(id),
      sourceConnectionId = UUID.fromString(scid),
      plaidItemId        = itemId,
      institutionName    = instName,
      cursor             = cursor,
      accessToken        = accessToken,
      createdAt          = createdAt.toInstant,
      updatedAt          = updatedAt.toInstant,
    )

  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  final class Live extends PlaidSyncRepository:

    def upsertConnection(
        sourceConnectionId: UUID,
        plaidItemId:        String,
        institutionName:    String,
        cursor:             Option[String],
        accessToken:        Option[String],
    ): ZIO[ZConnectionPool, AppError, PlaidConnectionRow] =
      val q =
        sql"INSERT INTO plaid.connections(source_connection_id, plaid_item_id, institution_name, cursor, access_token) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, $plaidItemId, $institutionName, $cursor, $accessToken) " ++
        sql"ON CONFLICT (plaid_item_id) DO UPDATE SET " ++
        sql"  institution_name = EXCLUDED.institution_name, " ++
        sql"  cursor           = COALESCE(EXCLUDED.cursor, plaid.connections.cursor), " ++
        sql"  access_token     = COALESCE(EXCLUDED.access_token, plaid.connections.access_token), " ++
        sql"  updated_at       = now() " ++
        sql"RETURNING " ++ connCols
      transaction(q.query[ConnRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("UPSERT plaid.connections returned no row"))))
        .map(rowToConn)

    def listBySourceConnectionId(
        sourceConnectionId: UUID,
    ): ZIO[ZConnectionPool, AppError, List[PlaidConnectionRow]] =
      val q =
        sql"SELECT " ++ connCols ++
        sql" FROM plaid.connections" ++
        sql" WHERE source_connection_id = ${sourceConnectionId.toString}::uuid" ++
        sql" ORDER BY created_at ASC"
      transaction(q.query[ConnRow].selectAll)
        .mapError(mapSqlError)
        .map(_.toList.map(rowToConn))

    def deleteConnection(
        sourceConnectionId: UUID,
        itemId:             UUID,
    ): ZIO[ZConnectionPool, AppError, Boolean] =
      val q =
        sql"DELETE FROM plaid.connections" ++
        sql" WHERE id = ${itemId.toString}::uuid" ++
        sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid"
      transaction(q.delete)
        .mapError(mapSqlError)
        .map(_ > 0L)

  val live: ZLayer[Any, Nothing, PlaidSyncRepository] =
    ZLayer.succeed(new Live)
