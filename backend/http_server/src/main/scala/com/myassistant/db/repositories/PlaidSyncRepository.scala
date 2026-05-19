package com.myassistant.db.repositories

import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.sql.SQLException
import java.time.{Instant, LocalDate}
import java.util.UUID

/** Domain record returned for a `plaid.connections` upsert. */
final case class PlaidConnectionRow(
    id:                 UUID,
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    createdAt:          Instant,
    updatedAt:          Instant,
)

/** Domain record returned for a `plaid.bank_accounts` upsert. */
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

/** Input record for one transaction inside a batch upsert. */
final case class PlaidTransactionInput(
    plaidTransactionId: String,
    amount:             BigDecimal,
    date:               LocalDate,
    merchantName:       Option[String],
    category:           List[String],
    paymentChannel:     Option[String],
    pending:            Boolean,
)

/** Data-access interface for the `plaid.*` schema tables. */
trait PlaidSyncRepository:

  /** Insert or update a `plaid.connections` row keyed on `plaid_item_id`. */
  def upsertConnection(
      sourceConnectionId: UUID,
      plaidItemId:        String,
      institutionName:    String,
      cursor:             Option[String],
  ): ZIO[ZConnectionPool, AppError, PlaidConnectionRow]

  /** Insert or update a `plaid.bank_accounts` row keyed on `plaid_account_id`. */
  def upsertBankAccount(
      sourceConnectionId: UUID,
      connectionId:       UUID,
      plaidAccountId:     String,
      name:               String,
      accountType:        String,
      currentBalance:     Option[BigDecimal],
  ): ZIO[ZConnectionPool, AppError, PlaidBankAccountRow]

  /** Batch upsert + delete transactions for a given account.
   *  Returns the counts of (added, modified, removed) that were
   *  effectively applied — added/modified counts mirror the input list
   *  lengths, removed reflects DELETE rowcount.
   */
  def upsertTransactionsBatch(
      sourceConnectionId: UUID,
      accountId:          UUID,
      added:              List[PlaidTransactionInput],
      modified:           List[PlaidTransactionInput],
      removed:            List[String],
  ): ZIO[ZConnectionPool, AppError, (Int, Int, Int)]

object PlaidSyncRepository:

  // ── plaid.connections row ─────────────────────────────────────────────────
  // id, source_connection_id, plaid_item_id, institution_name, cursor,
  // created_at, updated_at
  private type ConnRow =
    (String, String, String, String, Option[String],
     java.sql.Timestamp, java.sql.Timestamp)

  private val connCols = SqlFragment(
    """id::text, source_connection_id::text, plaid_item_id, institution_name,
       cursor, created_at, updated_at"""
  )

  private def rowToConn(row: ConnRow): PlaidConnectionRow =
    val (id, scid, itemId, instName, cursor, createdAt, updatedAt) = row
    PlaidConnectionRow(
      id                 = UUID.fromString(id),
      sourceConnectionId = UUID.fromString(scid),
      plaidItemId        = itemId,
      institutionName    = instName,
      cursor             = cursor,
      createdAt          = createdAt.toInstant,
      updatedAt          = updatedAt.toInstant,
    )

  // ── plaid.bank_accounts row ──────────────────────────────────────────────
  // id, source_connection_id, connection_id, plaid_account_id, name,
  // account_type, current_balance, created_at, updated_at
  private type AcctRow =
    (String, String, String, String, String, String,
     Option[BigDecimal], java.sql.Timestamp, java.sql.Timestamp)

  private val acctCols = SqlFragment(
    """id::text, source_connection_id::text, connection_id::text,
       plaid_account_id, name, account_type, current_balance,
       created_at, updated_at"""
  )

  private def rowToAcct(row: AcctRow): PlaidBankAccountRow =
    val (id, scid, cid, plaidAcctId, name, accountType, balance,
         createdAt, updatedAt) = row
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

  // ── SQL error mapper ──────────────────────────────────────────────────────
  private def mapSqlError(e: Throwable): AppError = e match
    case s: SQLException if s.getSQLState == "23505" => AppError.Conflict(s.getMessage)
    case s: SQLException if s.getSQLState == "23503" =>
      AppError.ReferentialIntegrityError(s.getMessage, Map.empty)
    case other => AppError.DatabaseError(other)

  /** Live implementation against PostgreSQL via zio-jdbc. */
  final class Live extends PlaidSyncRepository:

    def upsertConnection(
        sourceConnectionId: UUID,
        plaidItemId:        String,
        institutionName:    String,
        cursor:             Option[String],
    ): ZIO[ZConnectionPool, AppError, PlaidConnectionRow] =
      // ON CONFLICT cursor handling: when the caller passes a non-null cursor we
      // overwrite (this is the normal post-sync advance). When the caller passes
      // NULL we preserve the existing cursor via COALESCE so that the initial link
      // upsert and balance-only re-runs do not clobber a valid sync bookmark.
      val q =
        sql"INSERT INTO plaid.connections(source_connection_id, plaid_item_id, institution_name, cursor) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, $plaidItemId, $institutionName, $cursor) " ++
        sql"ON CONFLICT (plaid_item_id) DO UPDATE SET " ++
        sql"  institution_name = EXCLUDED.institution_name, " ++
        sql"  cursor = COALESCE(EXCLUDED.cursor, plaid.connections.cursor), " ++
        sql"  updated_at = now() " ++
        sql"RETURNING " ++ connCols
      transaction(q.query[ConnRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("UPSERT plaid.connections returned no row"))))
        .map(rowToConn)

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
        sql"  name = EXCLUDED.name, " ++
        sql"  account_type = EXCLUDED.account_type, " ++
        sql"  current_balance = EXCLUDED.current_balance, " ++
        sql"  updated_at = now() " ++
        sql"RETURNING " ++ acctCols
      transaction(q.query[AcctRow].selectOne)
        .mapError(mapSqlError)
        .flatMap(ZIO.fromOption(_).mapError(_ =>
          AppError.InternalError(new RuntimeException("UPSERT plaid.bank_accounts returned no row"))))
        .map(rowToAcct)

    // Build a PostgreSQL TEXT[] literal from a list of strings.
    // Each value is wrapped in double quotes and double-quotes are escaped.
    private def textArrayLiteral(values: List[String]): String =
      val escaped = values.map(v => "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      escaped.mkString("{", ",", "}")

    private def upsertOneTxn(
        sourceConnectionId: UUID,
        accountId:          UUID,
        txn:                PlaidTransactionInput,
    ): ZIO[ZConnectionPool, AppError, Unit] =
      val categoryLit: Option[String] =
        if txn.category.isEmpty then None
        else Some(textArrayLiteral(txn.category))
      val dateSqlStr = txn.date.toString  // "YYYY-MM-DD"
      val q =
        sql"INSERT INTO plaid.transactions(source_connection_id, account_id, plaid_transaction_id, amount, date, merchant_name, category, payment_channel, pending) " ++
        sql"VALUES (${sourceConnectionId.toString}::uuid, ${accountId.toString}::uuid, ${txn.plaidTransactionId}, ${txn.amount}, ${dateSqlStr}::date, ${txn.merchantName}, ${categoryLit}::text[], ${txn.paymentChannel}, ${txn.pending}) " ++
        sql"ON CONFLICT (plaid_transaction_id) DO UPDATE SET " ++
        sql"  amount = EXCLUDED.amount, " ++
        sql"  date = EXCLUDED.date, " ++
        sql"  merchant_name = EXCLUDED.merchant_name, " ++
        sql"  category = EXCLUDED.category, " ++
        sql"  payment_channel = EXCLUDED.payment_channel, " ++
        sql"  pending = EXCLUDED.pending"
      transaction(q.update)
        .mapError(mapSqlError)
        .unit

    def upsertTransactionsBatch(
        sourceConnectionId: UUID,
        accountId:          UUID,
        added:              List[PlaidTransactionInput],
        modified:           List[PlaidTransactionInput],
        removed:            List[String],
    ): ZIO[ZConnectionPool, AppError, (Int, Int, Int)] =
      for
        _ <- ZIO.foreachDiscard(added)(upsertOneTxn(sourceConnectionId, accountId, _))
        _ <- ZIO.foreachDiscard(modified)(upsertOneTxn(sourceConnectionId, accountId, _))
        removedCount <-
          if removed.isEmpty then ZIO.succeed(0L)
          else
            // Use a simple per-id DELETE loop to keep it portable; sum the rowcounts.
            ZIO.foldLeft(removed)(0L) { (acc, txnId) =>
              val q =
                sql"DELETE FROM plaid.transactions " ++
                sql" WHERE plaid_transaction_id = $txnId " ++
                sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid"
              transaction(q.delete).mapError(mapSqlError).map(acc + _)
            }
      yield (added.size, modified.size, removedCount.toInt)

  /** ZLayer providing the live PlaidSyncRepository. */
  val live: ZLayer[Any, Nothing, PlaidSyncRepository] =
    ZLayer.succeed(new Live)
