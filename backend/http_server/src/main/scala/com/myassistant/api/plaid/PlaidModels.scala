package com.myassistant.api.plaid

import io.circe.Codec
import java.util.UUID

// ── Route request / response models ────────────────────────────────────────

final case class LinkTokenRequest(personId: UUID) derives Codec.AsObject
final case class ExchangeRequest(personId: UUID, publicToken: String) derives Codec.AsObject
final case class LinkTokenResponse(linkToken: String) derives Codec.AsObject
final case class ExchangeResponse(itemId: String, institutionName: String) derives Codec.AsObject

// ── Plaid API response models (snake_case matching Plaid JSON) ─────────────

final case class PlaidLinkTokenResp(link_token: String) derives Codec.AsObject
final case class PlaidExchangeResp(access_token: String, item_id: String) derives Codec.AsObject

final case class PlaidBalance(
    current:           Option[Double],
    available:         Option[Double],
    iso_currency_code: Option[String],
) derives Codec.AsObject

final case class PlaidAccount(
    account_id:    String,
    name:          String,
    official_name: Option[String],
    `type`:        String,
    subtype:       Option[String],
    mask:          Option[String],
    balances:      PlaidBalance,
) derives Codec.AsObject

final case class PlaidItem(institution_id: Option[String]) derives Codec.AsObject

final case class PlaidAccountsResp(
    accounts: List[PlaidAccount],
    item:     PlaidItem,
) derives Codec.AsObject

final case class PlaidInstitution(name: String) derives Codec.AsObject
final case class PlaidInstitutionResp(institution: PlaidInstitution) derives Codec.AsObject

final case class PlaidCategory(primary: String, detailed: String) derives Codec.AsObject

final case class PlaidTransaction(
    transaction_id:            String,
    account_id:                String,
    amount:                    Double,
    iso_currency_code:         Option[String],
    merchant_name:             Option[String],
    name:                      String,
    personal_finance_category: Option[PlaidCategory],
    date:                      String,
    pending:                   Boolean,
) derives Codec.AsObject

final case class PlaidRemovedTransaction(transaction_id: String) derives Codec.AsObject

final case class PlaidSyncResp(
    added:       List[PlaidTransaction],
    modified:    List[PlaidTransaction],
    removed:     List[PlaidRemovedTransaction],
    next_cursor: String,
    has_more:    Boolean,
) derives Codec.AsObject
