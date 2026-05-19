"""Plaid scheduler handler — native plaid.* table architecture.

The new flow:
  1. Scheduler discovers due source_connections via /api/v1/source-connections/due.
  2. For each plaid_poll connection, this handler:
     a. Creates a sync_runs row (run_type='scheduled', status='running').
     b. Fetches secrets (access_token, item_id) from /api/v1/source-connections/{id}/secrets.
     c. Reads the persisted cursor from plaid.connections (best-effort via upsert).
     d. Calls Plaid /transactions/sync with paging until has_more=false.
     e. Calls Plaid /accounts/get for current balances + names.
     f. Upserts plaid.connections (advances cursor).
     g. Upserts each plaid.bank_accounts row (current_balance refresh).
     h. Sends added/modified/removed transactions in one batch to plaid.transactions.
     i. PATCHes the sync_run with terminal status / stats / log lines.
     j. Marks the source_connection synced and advances next_run_at via cron.

This replaces the previous document/fact-based implementation.
"""

import os
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx

from handlers.base import BaseHandler

_PLAID_CLIENT_ID = os.environ.get("PLAID_CLIENT_ID", "")
_PLAID_SECRET = os.environ.get("PLAID_SECRET", "")
_PLAID_ENV = os.environ.get("PLAID_ENV", "sandbox")

_PLAID_BASE = {
    "production":  "https://production.plaid.com",
    "development": "https://development.plaid.com",
}.get(_PLAID_ENV, "https://sandbox.plaid.com")

_SCHEDULER_TZ = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))


def _plaid_post(path: str, body: dict) -> dict:
    body = {**body, "client_id": _PLAID_CLIENT_ID, "secret": _PLAID_SECRET}
    resp = httpx.post(f"{_PLAID_BASE}{path}", json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _log_entry(level: str, msg: str) -> dict:
    return {
        "time":  datetime.now(timezone.utc).strftime("%H:%M:%S"),
        "level": level,
        "msg":   msg,
    }


class PlaidPollHandler(BaseHandler):
    """Plaid scheduled handler using source_connections + plaid.* tables."""

    def __init__(self, http: httpx.Client):
        self.http = http

    # ── Sync run lifecycle ────────────────────────────────────────────────

    def _create_scheduled_run(self, connection_id: str) -> str:
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/runs/create-scheduled",
            json={},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _patch_run(
        self,
        connection_id: str,
        run_id: str,
        status: str,
        stats: dict,
        log_lines: list[dict],
    ) -> None:
        body = {
            "status":      status,
            "completedAt": _now_iso(),
            "stats":       stats,
            "logLines":    log_lines,
        }
        resp = self.http.patch(
            f"/api/v1/source-connections/{connection_id}/runs/{run_id}",
            json=body,
        )
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: PATCH run failed: {resp.status_code} {resp.text[:200]}")

    def _mark_synced(self, connection_id: str) -> None:
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/mark-synced",
            json={"lastSyncedAt": _now_iso()},
        )
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: mark-synced failed: {resp.status_code} {resp.text[:200]}")

    def _advance_next_run(self, connection_id: str, cron_expression: str | None) -> None:
        try:
            from croniter import croniter
            cron_expr = cron_expression or "0 2 * * *"
            cron = croniter(cron_expr, datetime.now(_SCHEDULER_TZ))
            next_run = cron.get_next(datetime).astimezone(timezone.utc)
        except Exception:
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/advance",
            json={"nextRunAt": next_run.isoformat()},
        )
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: advance failed: {resp.status_code} {resp.text[:200]}")

    # ── Secrets fetch ─────────────────────────────────────────────────────

    def _fetch_secrets(self, connection_id: str) -> dict | None:
        resp = self.http.get(f"/api/v1/source-connections/{connection_id}/secrets")
        resp.raise_for_status()
        return resp.json().get("secrets")

    # ── plaid.* upserts ───────────────────────────────────────────────────

    def _upsert_plaid_connection(
        self,
        source_connection_id: str,
        plaid_item_id: str,
        institution_name: str,
        cursor: str | None,
    ) -> dict:
        resp = self.http.post("/api/v1/plaid/connections/upsert", json={
            "sourceConnectionId": source_connection_id,
            "plaidItemId":        plaid_item_id,
            "institutionName":    institution_name,
            "cursor":             cursor,
        })
        resp.raise_for_status()
        return resp.json()

    def _upsert_plaid_account(
        self,
        source_connection_id: str,
        connection_id: str,
        plaid_account_id: str,
        name: str,
        account_type: str,
        current_balance: float | None,
    ) -> dict:
        resp = self.http.post("/api/v1/plaid/accounts/upsert", json={
            "sourceConnectionId": source_connection_id,
            "connectionId":       connection_id,
            "plaidAccountId":     plaid_account_id,
            "name":               name,
            "accountType":        account_type,
            "currentBalance":     current_balance,
        })
        resp.raise_for_status()
        return resp.json()

    def _send_transactions_batch(
        self,
        source_connection_id: str,
        account_id: str,
        added: list[dict],
        modified: list[dict],
        removed_ids: list[str],
    ) -> dict:
        resp = self.http.post("/api/v1/plaid/transactions/batch", json={
            "sourceConnectionId":         source_connection_id,
            "accountId":                  account_id,
            "added":                      added,
            "modified":                   modified,
            "removedPlaidTransactionIds": removed_ids,
        })
        resp.raise_for_status()
        return resp.json()

    # ── Plaid → batch payload mapping ─────────────────────────────────────

    @staticmethod
    def _txn_to_payload(txn: dict) -> dict:
        cat = txn.get("personal_finance_category") or {}
        category_list = [c for c in [cat.get("primary"), cat.get("detailed")] if c]
        return {
            "plaidTransactionId": txn["transaction_id"],
            "amount":             txn["amount"],
            "date":               txn["date"],
            "merchantName":       txn.get("merchant_name") or txn.get("name"),
            "category":           category_list,
            "paymentChannel":     txn.get("payment_channel"),
            "pending":            txn.get("pending", False),
        }

    # ── Main entry point ──────────────────────────────────────────────────

    def run(self, source_connection: dict) -> None:
        """Execute one scheduled Plaid sync for the given source_connection row.

        `source_connection` is the SourceConnectionResponse JSON.
        """
        connection_id = source_connection["id"]
        cron_expression = source_connection.get("syncSchedule")
        config = source_connection.get("config") or {}
        institution_name = config.get("institution_name", "Unknown Institution")

        log_lines: list[dict] = []
        stats = {"added": 0, "modified": 0, "removed": 0, "accounts_checked": 0, "errors": 0}
        terminal_status = "running"
        run_id: str | None = None

        try:
            run_id = self._create_scheduled_run(connection_id)
            log_lines.append(_log_entry("info", f"Starting Plaid sync for {institution_name}"))
            print(f"[plaid_poll] connection {connection_id}: started run {run_id}")

            secrets = self._fetch_secrets(connection_id)
            if not secrets or not secrets.get("access_token"):
                log_lines.append(_log_entry("error", "No access_token in source_connection secrets"))
                stats["errors"] += 1
                terminal_status = "failed"
                return

            access_token = secrets["access_token"]
            item_id = secrets.get("item_id", "")

            # Step 1: ensure the plaid.connections row exists and read its
            # current cursor. The upsert with cursor=None is cursor-preserving
            # (COALESCE on the server side) so this is safe even on subsequent
            # runs — the existing cursor is returned in the response and used
            # as the starting point for /transactions/sync.
            plaid_conn = self._upsert_plaid_connection(
                source_connection_id=connection_id,
                plaid_item_id=item_id,
                institution_name=institution_name,
                cursor=None,
            )
            plaid_conn_id = plaid_conn["id"]
            cursor: str | None = plaid_conn.get("cursor")
            log_lines.append(_log_entry(
                "info",
                f"Resuming Plaid sync from cursor={'<fresh>' if not cursor else '<resume>'}",
            ))

            all_added: list[dict] = []
            all_modified: list[dict] = []
            all_removed: list[dict] = []
            next_cursor: str | None = cursor

            while True:
                body = {"access_token": access_token}
                if next_cursor:
                    body["cursor"] = next_cursor
                sync_resp = _plaid_post("/transactions/sync", body)
                batch_added = sync_resp.get("added", [])
                batch_modified = sync_resp.get("modified", [])
                batch_removed = sync_resp.get("removed", [])
                has_more = sync_resp.get("has_more", False)
                all_added.extend(batch_added)
                all_modified.extend(batch_modified)
                all_removed.extend(batch_removed)
                next_cursor = sync_resp.get("next_cursor", "") or next_cursor
                log_lines.append(_log_entry(
                    "info",
                    f"Plaid /transactions/sync batch: +{len(batch_added)} ~{len(batch_modified)} "
                    f"-{len(batch_removed)} has_more={has_more}",
                ))
                if not has_more:
                    break

            # /accounts/get — current names + balances.
            accounts_resp = _plaid_post("/accounts/get", {"access_token": access_token})
            accounts = accounts_resp.get("accounts", [])
            log_lines.append(_log_entry("info", f"Plaid /accounts/get returned {len(accounts)} account(s)"))

            # Advance plaid.connections.cursor to the new value emitted by
            # the final /transactions/sync page.
            if next_cursor and next_cursor != cursor:
                plaid_conn = self._upsert_plaid_connection(
                    source_connection_id=connection_id,
                    plaid_item_id=item_id,
                    institution_name=institution_name,
                    cursor=next_cursor,
                )
                plaid_conn_id = plaid_conn["id"]

            # Upsert plaid.bank_accounts; build plaid_account_id -> bank_account row id map.
            plaid_acct_to_row_id: dict[str, str] = {}
            for account in accounts:
                balances = account.get("balances") or {}
                row = self._upsert_plaid_account(
                    source_connection_id=connection_id,
                    connection_id=plaid_conn_id,
                    plaid_account_id=account["account_id"],
                    name=account["name"],
                    account_type=account.get("type", "other"),
                    current_balance=balances.get("current"),
                )
                plaid_acct_to_row_id[account["account_id"]] = row["id"]
                stats["accounts_checked"] += 1

            # Group transactions by account_id (Plaid uses the plaid_account_id).
            added_by_acct: dict[str, list[dict]] = {}
            modified_by_acct: dict[str, list[dict]] = {}
            for txn in all_added:
                acct_id = txn["account_id"]
                added_by_acct.setdefault(acct_id, []).append(self._txn_to_payload(txn))
            for txn in all_modified:
                acct_id = txn["account_id"]
                modified_by_acct.setdefault(acct_id, []).append(self._txn_to_payload(txn))

            # Removed transactions don't include the account_id reliably; Plaid emits
            # {transaction_id, account_id?}. Group by account_id when present; otherwise
            # send them all under the first known account. (DELETE is filtered by
            # source_connection_id server-side so this is safe — only matching ids are
            # actually removed.)
            removed_by_acct: dict[str, list[str]] = {}
            for r in all_removed:
                acct_plaid_id = r.get("account_id")
                txn_plaid_id = r.get("transaction_id")
                if not txn_plaid_id:
                    continue
                if acct_plaid_id and acct_plaid_id in plaid_acct_to_row_id:
                    removed_by_acct.setdefault(acct_plaid_id, []).append(txn_plaid_id)
                else:
                    # Fallback bucket: any known account
                    fallback = next(iter(plaid_acct_to_row_id.keys()), None)
                    if fallback:
                        removed_by_acct.setdefault(fallback, []).append(txn_plaid_id)

            # Send one batch per account.
            all_acct_keys = set(added_by_acct) | set(modified_by_acct) | set(removed_by_acct)
            for acct_plaid_id in all_acct_keys:
                row_id = plaid_acct_to_row_id.get(acct_plaid_id)
                if not row_id:
                    log_lines.append(_log_entry(
                        "warn",
                        f"Skipping {acct_plaid_id}: no matching plaid.bank_accounts row",
                    ))
                    stats["errors"] += 1
                    continue
                batch_resp = self._send_transactions_batch(
                    source_connection_id=connection_id,
                    account_id=row_id,
                    added=added_by_acct.get(acct_plaid_id, []),
                    modified=modified_by_acct.get(acct_plaid_id, []),
                    removed_ids=removed_by_acct.get(acct_plaid_id, []),
                )
                stats["added"]    += batch_resp.get("added", 0)
                stats["modified"] += batch_resp.get("modified", 0)
                stats["removed"] += batch_resp.get("removed", 0)

            log_lines.append(_log_entry(
                "info",
                f"Sync complete: +{stats['added']} ~{stats['modified']} -{stats['removed']} "
                f"across {stats['accounts_checked']} account(s)",
            ))

            if stats["errors"] > 0:
                terminal_status = "warning"
            else:
                terminal_status = "success"

        except Exception as e:
            log_lines.append(_log_entry("error", f"Fatal: {e}"))
            stats["errors"] += 1
            terminal_status = "failed"
            print(f"[plaid_poll] connection {connection_id}: fatal error: {e}")

        finally:
            if run_id is not None:
                self._patch_run(connection_id, run_id, terminal_status, stats, log_lines)
            if terminal_status in ("success", "warning"):
                self._mark_synced(connection_id)
            self._advance_next_run(connection_id, cron_expression)
            print(
                f"[plaid_poll] connection {connection_id}: status={terminal_status} stats={stats}"
            )
