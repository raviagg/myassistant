package com.myassistant.api.models

import com.myassistant.db.repositories.{PlaidBankAccountRow, PlaidConnectionRow}
import io.circe.Codec

import java.time.{Instant, LocalDate}
import java.util.UUID

// ── plaid.connections upsert ──────────────────────────────────────────────

/** Request body for POST /api/v1/plaid/connections/upsert. */
final case class UpsertPlaidConnectionRequest(
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
) derives Codec.AsObject

/** Response body returned from the connection upsert endpoint. */
final case class PlaidConnectionResponse(
    id:                 UUID,
    sourceConnectionId: UUID,
    plaidItemId:        String,
    institutionName:    String,
    cursor:             Option[String],
    createdAt:          Instant,
    updatedAt:          Instant,
) derives Codec.AsObject

object PlaidConnectionResponse:
  def fromDomain(c: PlaidConnectionRow): PlaidConnectionResponse =
    PlaidConnectionResponse(
      id                 = c.id,
      sourceConnectionId = c.sourceConnectionId,
      plaidItemId        = c.plaidItemId,
      institutionName    = c.institutionName,
      cursor             = c.cursor,
      createdAt          = c.createdAt,
      updatedAt          = c.updatedAt,
    )

// ── plaid.bank_accounts upsert ────────────────────────────────────────────

/** Request body for POST /api/v1/plaid/accounts/upsert. */
final case class UpsertPlaidBankAccountRequest(
    sourceConnectionId: UUID,
    connectionId:       UUID,
    plaidAccountId:     String,
    name:               String,
    accountType:        String,
    currentBalance:     Option[BigDecimal],
) derives Codec.AsObject

final case class PlaidBankAccountResponse(
    id:                 UUID,
    sourceConnectionId: UUID,
    connectionId:       UUID,
    plaidAccountId:     String,
    name:               String,
    accountType:        String,
    currentBalance:     Option[BigDecimal],
    createdAt:          Instant,
    updatedAt:          Instant,
) derives Codec.AsObject

object PlaidBankAccountResponse:
  def fromDomain(a: PlaidBankAccountRow): PlaidBankAccountResponse =
    PlaidBankAccountResponse(
      id                 = a.id,
      sourceConnectionId = a.sourceConnectionId,
      connectionId       = a.connectionId,
      plaidAccountId     = a.plaidAccountId,
      name               = a.name,
      accountType        = a.accountType,
      currentBalance     = a.currentBalance,
      createdAt          = a.createdAt,
      updatedAt          = a.updatedAt,
    )

// ── plaid.transactions batch ──────────────────────────────────────────────

/** Single transaction in a batch upsert. */
final case class PlaidTransactionWrite(
    plaidTransactionId: String,
    amount:             BigDecimal,
    date:               LocalDate,
    merchantName:       Option[String],
    category:           Option[List[String]],
    paymentChannel:     Option[String],
    pending:            Option[Boolean],
) derives Codec.AsObject

final case class PlaidTransactionsBatchRequest(
    sourceConnectionId:          UUID,
    accountId:                   UUID,
    added:                       Option[List[PlaidTransactionWrite]],
    modified:                    Option[List[PlaidTransactionWrite]],
    removedPlaidTransactionIds:  Option[List[String]],
) derives Codec.AsObject

final case class PlaidTransactionsBatchResponse(
    added:   Int,
    modified: Int,
    removed: Int,
) derives Codec.AsObject
