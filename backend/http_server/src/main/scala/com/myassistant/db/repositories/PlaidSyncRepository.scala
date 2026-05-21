package com.myassistant.db.repositories

import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.{Instant, LocalDate}
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

final case class PlaidBankAccountRow(
    id:                 UUID,
    sourceConnectionId: UUID,
    connectionId:       UUID,
    plaidAccountId:     String,
    name:               String,
    accountType:        String,
    currentBalance:     Option[BigDecimal],
    createdAt:          Instant,
    updatedAt:          Instant,
)

final case class PlaidTransactionInput(
    plaidTransactionId: String,
    amount:             BigDecimal,
    date:               LocalDate,
    merchantName:       Option[String],
    category:           List[String],
    paymentChannel:     Option[String],
    pending:            Boolean,
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

  def upsertBankAccount(
      sourceConnectionId: UUID,
      connectionId:       UUID,
      plaidAccountId:     String,
      name:               String,
      accountType:        String,
      currentBalance:     Option[BigDecimal],
  ): ZIO[ZConnectionPool, AppError, PlaidBankAccountRow]

  def upsertTransactionsBatch(
      sourceConnectionId: UUID,
      accountId:          UUID,
      added:              List[PlaidTransactionInput],
      modified:           List[PlaidTransactionInput],
      removed:            List[String],
  ): ZIO[ZConnectionPool, AppError, (Int, Int, Int)]

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

  private type AcctRow =
    (String, String, String, String, String, String,
     Option[BigDecimal], java.sql.Timestamp, java.sql.Timestamp)

  private val acctCols = SqlFragment(
    """id::text, source_connection_id::text, connection_id::text,
       plaid_account_id, name, account_type, current_balance,
       created_at, updated_at"""
  )

  private def rowToAcct(row: AcctRow): PlaidBankAccountRow =
    val (id, scid, cid, plaidAcctId, name, accountType, balance, createdAt, updatedAt) = row
    PlaidBankAccountRow(
      id                 = UUID.fromString(id),
      sourceConnectionId = UUID.fromString(scid),
      connectionId       = UUID.fromString(cid),
      plaidAccountId     = plaidAcctId,
      name               = name,
      accountType        = accountType,
      currentBalance     = balance,
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

    def upsertBankAccount(
        sourceConnectionId: UUID,
        connectionId:       UUID,
        plaidAccountId:     String,
        name:               String,
        accountType:        String,
        currentBalance:     Option[BigDecimal],
    ): ZIO[ZConnectionPool, AppError, PlaidBankAccountRow] =
      val q =
        sql"INSERT INTO plaid.bank_accounts(source_connection_id, connection_id, plaid_account_id, name, account_type, current_balance) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, ${connectionId.toString}::uuid, $plaidAccountId, $name, $accountType, $currentBalance) " ++
        sql"ON CONFLICT (plaid_account_id) DO UPDATE SET " ++
        sql"  name            = EXCLUDED.name, " ++
        sql"  account_type    = EXCLUDED.account_type, " ++
        sql"  current_balance = EXCLUDED.current_balance, " ++
        sql"  updated_at      = now() " ++
        sql"RETURNING " ++ acctCols
      transaction(q.query[AcctRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("UPSERT plaid.bank_accounts returned no row"))))
        .map(rowToAcct)

    private def toJsonArray(values: List[String]): String =
      values
        .map(v => "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .mkString("[", ",", "]")

    private def upsertOneTxnOp(
        sourceConnectionId: UUID,
        accountId:          UUID,
        txn:                PlaidTransactionInput,
    ): ZIO[ZConnection, Throwable, Unit] =
      val dateSqlStr = txn.date.toString
      val categoryFrag =
        if txn.category.isEmpty then sql"NULL::text[]"
        else
          val json = toJsonArray(txn.category)
          sql"ARRAY(SELECT jsonb_array_elements_text(${json}::jsonb))"
      val q =
        sql"INSERT INTO plaid.transactions(source_connection_id, account_id, plaid_transaction_id, amount, date, merchant_name, category, payment_channel, pending) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, ${accountId.toString}::uuid, ${txn.plaidTransactionId}, ${txn.amount}, ${dateSqlStr}::date, ${txn.merchantName}, " ++
        categoryFrag ++
        sql", ${txn.paymentChannel}, ${txn.pending}) " ++
        sql"ON CONFLICT (plaid_transaction_id) DO UPDATE SET " ++
        sql"  amount = EXCLUDED.amount, " ++
        sql"  date = EXCLUDED.date, " ++
        sql"  merchant_name = EXCLUDED.merchant_name, " ++
        sql"  category = EXCLUDED.category, " ++
        sql"  payment_channel = EXCLUDED.payment_channel, " ++
        sql"  pending = EXCLUDED.pending"
      q.update.unit

    def upsertTransactionsBatch(
        sourceConnectionId: UUID,
        accountId:          UUID,
        added:              List[PlaidTransactionInput],
        modified:           List[PlaidTransactionInput],
        removed:            List[String],
    ): ZIO[ZConnectionPool, AppError, (Int, Int, Int)] =
      transaction {
        for
          _ <- ZIO.foreachDiscard(added)(upsertOneTxnOp(sourceConnectionId, accountId, _))
          _ <- ZIO.foreachDiscard(modified)(upsertOneTxnOp(sourceConnectionId, accountId, _))
          removedCount <-
            if removed.isEmpty then ZIO.succeed(0L)
            else
              ZIO.foldLeft(removed)(0L) { (acc, txnId) =>
                val q =
                  sql"DELETE FROM plaid.transactions " ++
                  sql" WHERE plaid_transaction_id = $txnId " ++
                  sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid"
                q.delete.map(acc + _)
              }
        yield (added.size, modified.size, removedCount.toInt)
      }.mapError(mapSqlError)

  val live: ZLayer[Any, Nothing, PlaidSyncRepository] =
    ZLayer.succeed(new Live)
