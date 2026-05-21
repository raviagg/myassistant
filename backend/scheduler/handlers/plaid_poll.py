"""Plaid scheduler handler — two-level source_connections architecture.

Flow:
  source_connections row = one Plaid integration per person (holds client_id + secret)
  plaid.connections rows = one per linked bank (holds access_token)

Per source_connection run:
  1. Create a sync_run (scheduled, running).
  2. Fetch integration credentials from /api/v1/source-connections/{id}/secrets
     → { client_id, secret }
  3. List linked banks from /api/v1/source-connections/{id}/plaid/items
     → [{ id, plaid_item_id, institution_name, cursor, access_token }]
  4. For each bank item: run /transactions/sync + /accounts/get, upsert results.
  5. Patch sync_run with terminal status and stats.
  6. Mark source_connection synced; advance next_run_at.
"""

import os
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx

from handlers.base import BaseHandler

_PLAID_ENV = os.environ.get("PLAID_ENV", "sandbox")

_PLAID_BASE = {
    "production":  "https://production.plaid.com",
    "development": "https://development.plaid.com",
}.get(_PLAID_ENV, "https://sandbox.plaid.com")

_SCHEDULER_TZ = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))


def _plaid_post(path: str, body: dict, client_id: str, secret: str) -> dict:
    body = {**body, "client_id": client_id, "secret": secret}
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
    """Plaid scheduled handler — one run per Plaid integration connection."""

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

    # ── Data fetchers ─────────────────────────────────────────────────────

    def _fetch_plaid_creds(self, connection_id: str) -> tuple[str, str]:
        """Return (client_id, secret) from integration source_connection secrets."""
        resp = self.http.get(f"/api/v1/source-connections/{connection_id}/secrets")
        resp.raise_for_status()
        secrets = resp.json().get("secrets") or {}
        client_id = secrets.get("client_id", "")
        secret    = secrets.get("secret", "")
        if not client_id or not secret:
            raise ValueError(f"Missing Plaid credentials in source_connection {connection_id}")
        return client_id, secret

    def _list_plaid_items(self, connection_id: str) -> list[dict]:
        """Return list of plaid.connections items with decrypted access_token."""
        resp = self.http.get(f"/api/v1/source-connections/{connection_id}/plaid/items")
        resp.raise_for_status()
        return resp.json()

    # ── plaid.* upserts (via Scala API) ──────────────────────────────────

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
            "accessToken":        None,  # preserve existing via COALESCE
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

    # ── Per-item sync ─────────────────────────────────────────────────────

    def _sync_item(
        self,
        source_connection_id: str,
        item: dict,
        client_id: str,
        secret: str,
        stats: dict,
        log_lines: list[dict],
    ) -> None:
        """Sync one plaid.connections item (one bank)."""
        access_token     = item.get("accessToken", "")
        plaid_item_id    = item.get("plaidItemId", "")
        institution_name = item.get("institutionName", "Unknown")
        cursor           = item.get("cursor")
        plaid_conn_id    = item["id"]

        if not access_token:
            log_lines.append(_log_entry("error", f"No access_token for item {plaid_item_id}"))
            stats["errors"] += 1
            return

        log_lines.append(_log_entry("info", f"Syncing {institution_name} (item {plaid_item_id})"))

        all_added:   list[dict] = []
        all_modified: list[dict] = []
        all_removed: list[dict] = []
        next_cursor = cursor

        while True:
            body: dict = {"access_token": access_token}
            if next_cursor:
                body["cursor"] = next_cursor
            sync_resp  = _plaid_post("/transactions/sync", body, client_id, secret)
            batch_added    = sync_resp.get("added", [])
            batch_modified = sync_resp.get("modified", [])
            batch_removed  = sync_resp.get("removed", [])
            has_more       = sync_resp.get("has_more", False)
            all_added.extend(batch_added)
            all_modified.extend(batch_modified)
            all_removed.extend(batch_removed)
            next_cursor = sync_resp.get("next_cursor", "") or next_cursor
            log_lines.append(_log_entry(
                "info",
                f"  /transactions/sync: +{len(batch_added)} ~{len(batch_modified)} "
                f"-{len(batch_removed)} has_more={has_more}",
            ))
            if not has_more:
                break

        accounts_resp = _plaid_post("/accounts/get", {"access_token": access_token}, client_id, secret)
        accounts      = accounts_resp.get("accounts", [])

        if (next_cursor or "") != (cursor or ""):
            updated = self._upsert_plaid_connection(
                source_connection_id=source_connection_id,
                plaid_item_id=plaid_item_id,
                institution_name=institution_name,
                cursor=next_cursor,
            )
            plaid_conn_id = updated["id"]

        plaid_acct_to_row_id: dict[str, str] = {}
        for account in accounts:
            balances = account.get("balances") or {}
            row = self._upsert_plaid_account(
                source_connection_id=source_connection_id,
                connection_id=plaid_conn_id,
                plaid_account_id=account["account_id"],
                name=account["name"],
                account_type=account.get("type", "other"),
                current_balance=balances.get("current"),
            )
            plaid_acct_to_row_id[account["account_id"]] = row["id"]
            stats["accounts_checked"] += 1

        added_by_acct:    dict[str, list[dict]] = {}
        modified_by_acct: dict[str, list[dict]] = {}
        removed_by_acct:  dict[str, list[str]]  = {}

        for txn in all_added:
            added_by_acct.setdefault(txn["account_id"], []).append(self._txn_to_payload(txn))
        for txn in all_modified:
            modified_by_acct.setdefault(txn["account_id"], []).append(self._txn_to_payload(txn))
        for r in all_removed:
            acct_plaid_id = r.get("account_id")
            txn_plaid_id  = r.get("transaction_id")
            if not txn_plaid_id:
                continue
            if acct_plaid_id and acct_plaid_id in plaid_acct_to_row_id:
                removed_by_acct.setdefault(acct_plaid_id, []).append(txn_plaid_id)
            else:
                log_lines.append(_log_entry("warn", f"Removed txn {txn_plaid_id}: unknown account_id {acct_plaid_id!r}, skipping"))

        for acct_plaid_id in set(added_by_acct) | set(modified_by_acct) | set(removed_by_acct):
            row_id = plaid_acct_to_row_id.get(acct_plaid_id)
            if not row_id:
                log_lines.append(_log_entry("warn", f"Skipping {acct_plaid_id}: no matching row"))
                stats["errors"] += 1
                continue
            batch_resp = self._send_transactions_batch(
                source_connection_id=source_connection_id,
                account_id=row_id,
                added=added_by_acct.get(acct_plaid_id, []),
                modified=modified_by_acct.get(acct_plaid_id, []),
                removed_ids=removed_by_acct.get(acct_plaid_id, []),
            )
            stats["added"]    += batch_resp.get("added", 0)
            stats["modified"] += batch_resp.get("modified", 0)
            stats["removed"]  += batch_resp.get("removed", 0)

    # ── Main entry point ──────────────────────────────────────────────────

    def run(self, source_connection: dict) -> None:
        """Execute one scheduled Plaid sync for the given integration source_connection."""
        connection_id   = source_connection["id"]
        cron_expression = source_connection.get("syncSchedule")

        log_lines: list[dict] = []
        stats = {"added": 0, "modified": 0, "removed": 0, "accounts_checked": 0, "errors": 0}
        terminal_status = "running"
        run_id: str | None = None

        try:
            run_id = self._create_scheduled_run(connection_id)
            log_lines.append(_log_entry("info", "Starting Plaid integration sync"))
            print(f"[plaid_poll] connection {connection_id}: started run {run_id}")

            client_id, secret = self._fetch_plaid_creds(connection_id)

            items = self._list_plaid_items(connection_id)
            if not items:
                log_lines.append(_log_entry("warn", "No linked bank items found — nothing to sync"))
                terminal_status = "success"
                return

            log_lines.append(_log_entry("info", f"Found {len(items)} linked bank(s)"))

            for item in items:
                try:
                    self._sync_item(connection_id, item, client_id, secret, stats, log_lines)
                except Exception as e:
                    log_lines.append(_log_entry("error", f"Item {item.get('plaidItemId')} failed: {e}"))
                    stats["errors"] += 1

            log_lines.append(_log_entry(
                "info",
                f"Sync complete: +{stats['added']} ~{stats['modified']} -{stats['removed']} "
                f"across {stats['accounts_checked']} account(s)",
            ))

            terminal_status = "warning" if stats["errors"] > 0 else "success"

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
            if run_id is not None:
                self._advance_next_run(connection_id, cron_expression)
            print(f"[plaid_poll] connection {connection_id}: status={terminal_status} stats={stats}")
