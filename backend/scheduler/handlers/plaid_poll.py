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
  4. For each bank item: run /transactions/sync + /accounts/get, write to document/fact pipeline.
  5. Patch sync_run with terminal status and stats.
  6. Mark source_connection synced; advance next_run_at.
"""

import json as _json
import os
import uuid
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
        self._plaid_poll_source_type_id: str | None = None
        self._finance_domain_id: str | None = None
        self._transaction_schema_id: str | None = None
        self._account_schema_id: str | None = None

    # ── Reference data (cached per handler instance) ──────────────────────

    def _get_plaid_poll_source_type_id(self) -> str:
        if self._plaid_poll_source_type_id is None:
            resp = self.http.get("/api/v1/reference/source-types")
            resp.raise_for_status()
            match = next((st for st in resp.json().get("items", []) if st["name"] == "plaid_poll"), None)
            if match is None:
                raise RuntimeError("plaid_poll source type not found in reference data")
            self._plaid_poll_source_type_id = match["id"]
        return self._plaid_poll_source_type_id

    def _get_finance_domain_id(self) -> str:
        if self._finance_domain_id is None:
            resp = self.http.get("/api/v1/reference/domains")
            resp.raise_for_status()
            match = next((d for d in resp.json().get("items", []) if d["name"] == "finance"), None)
            if match is None:
                raise RuntimeError("finance domain not found in reference data")
            self._finance_domain_id = match["id"]
        return self._finance_domain_id

    def _get_schema_id(self, entity_type: str) -> str:
        resp = self.http.get(
            "/api/v1/schemas/current",
            params={"domainId": self._get_finance_domain_id(), "entityType": entity_type},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _get_transaction_schema_id(self) -> str:
        if self._transaction_schema_id is None:
            self._transaction_schema_id = self._get_schema_id("transaction")
        return self._transaction_schema_id

    def _get_account_schema_id(self) -> str:
        if self._account_schema_id is None:
            self._account_schema_id = self._get_schema_id("bank_account")
        return self._account_schema_id

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

    # ── plaid.connections cursor store ───────────────────────────────────

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

    # ── Document/fact pipeline writers ───────────────────────────────────

    def _write_account_to_pipeline(
        self,
        source_connection_id: str,
        person_id: str,
        account: dict,
        embed,
    ) -> None:
        name = account["name"]
        account_type = account.get("type", "other")
        subtype = account.get("subtype") or ""
        balances = account.get("balances") or {}
        current_balance = balances.get("current")
        available_balance = balances.get("available")
        iso_currency = balances.get("iso_currency_code") or ""
        mask = account.get("mask") or ""
        plaid_account_id = account["account_id"]

        content_text = f"{name} ({account_type}) — balance: {current_balance} {iso_currency}"
        doc_resp = self.http.post("/api/v1/documents", json={
            "contentText":   content_text,
            "sourceTypeId":  self._get_plaid_poll_source_type_id(),
            "embedding":     embed(content_text),
            "supersedesIds": [],
            "files":         [],
            "personId":      person_id,
        })
        doc_resp.raise_for_status()
        doc_id = doc_resp.json()["id"]

        fields: dict = {
            "account_id":  plaid_account_id,
            "name":        name,
            "type":        account_type,
        }
        if subtype:
            fields["subtype"] = subtype
        if mask:
            fields["mask"] = mask
        if current_balance is not None:
            fields["current_balance"] = current_balance
        if available_balance is not None:
            fields["available_balance"] = available_balance
        if iso_currency:
            fields["iso_currency_code"] = iso_currency

        fact_resp = self.http.post("/api/v1/facts", json={
            "documentId":         doc_id,
            "schemaId":           self._get_account_schema_id(),
            "entityInstanceId":   str(uuid.uuid5(uuid.NAMESPACE_URL, plaid_account_id)),
            "operationType":      "create",
            "fields":             fields,
            "embedding":          embed(_json.dumps(fields, sort_keys=True)),
            "sourceConnectionId": source_connection_id,
        })
        fact_resp.raise_for_status()

    def _write_transaction_to_pipeline(
        self,
        source_connection_id: str,
        person_id: str,
        txn: dict,
        operation_type: str,  # "create" or "update"
        embed,
    ) -> None:
        plaid_txn_id = txn["transaction_id"]
        amount = txn["amount"]
        date = txn["date"]
        merchant_name = txn.get("merchant_name") or txn.get("name") or ""
        iso_currency = txn.get("iso_currency_code") or ""
        pending = txn.get("pending", False)
        cat = txn.get("personal_finance_category") or {}
        category_list = [c for c in [cat.get("primary"), cat.get("detailed")] if c]

        content_text = f"{merchant_name} — {amount} {iso_currency} on {date}"
        doc_resp = self.http.post("/api/v1/documents", json={
            "contentText":   content_text,
            "sourceTypeId":  self._get_plaid_poll_source_type_id(),
            "embedding":     embed(content_text),
            "supersedesIds": [],
            "files":         [],
            "personId":      person_id,
        })
        doc_resp.raise_for_status()
        doc_id = doc_resp.json()["id"]

        fields: dict = {
            "transaction_id":  plaid_txn_id,
            "account_id":      txn["account_id"],
            "bank_account_id": str(uuid.uuid5(uuid.NAMESPACE_URL, txn["account_id"])),
            "amount":          amount,
            "date":            date,
        }
        if merchant_name:
            fields["merchant_name"] = merchant_name
        if category_list:
            fields["category"] = category_list
        if iso_currency:
            fields["iso_currency_code"] = iso_currency
        fields["pending"] = pending

        fact_resp = self.http.post("/api/v1/facts", json={
            "documentId":         doc_id,
            "schemaId":           self._get_transaction_schema_id(),
            "entityInstanceId":   str(uuid.uuid5(uuid.NAMESPACE_URL, plaid_txn_id)),
            "operationType":      operation_type,
            "fields":             fields,
            "embedding":          embed(_json.dumps(fields, sort_keys=True)),
            "sourceConnectionId": source_connection_id,
        })
        fact_resp.raise_for_status()

    def _delete_transaction_in_pipeline(
        self,
        source_connection_id: str,
        person_id: str,
        plaid_txn_id: str,
        embed,
    ) -> None:
        content_text = f"Transaction {plaid_txn_id} removed"
        doc_resp = self.http.post("/api/v1/documents", json={
            "contentText":   content_text,
            "sourceTypeId":  self._get_plaid_poll_source_type_id(),
            "embedding":     embed(content_text),
            "supersedesIds": [],
            "files":         [],
            "personId":      person_id,
        })
        doc_resp.raise_for_status()
        doc_id = doc_resp.json()["id"]

        fact_resp = self.http.post("/api/v1/facts", json={
            "documentId":         doc_id,
            "schemaId":           self._get_transaction_schema_id(),
            "entityInstanceId":   str(uuid.uuid5(uuid.NAMESPACE_URL, plaid_txn_id)),
            "operationType":      "delete",
            "fields":             {},
            "embedding":          embed("{}"),
            "sourceConnectionId": source_connection_id,
        })
        fact_resp.raise_for_status()

    # ── Per-item sync ─────────────────────────────────────────────────────

    def _sync_item(
        self,
        source_connection_id: str,
        person_id: str,
        item: dict,
        client_id: str,
        secret: str,
        stats: dict,
        log_lines: list[dict],
    ) -> None:
        """Sync one plaid.connections item (one bank)."""
        from embed import embed

        access_token     = item.get("accessToken", "")
        plaid_item_id    = item.get("plaidItemId", "")
        institution_name = item.get("institutionName", "Unknown")
        cursor           = item.get("cursor")

        if not access_token:
            log_lines.append(_log_entry("error", f"No access_token for item {plaid_item_id}"))
            stats["errors"] += 1
            return

        log_lines.append(_log_entry("info", f"Syncing {institution_name} (item {plaid_item_id})"))

        all_added:    list[dict] = []
        all_modified: list[dict] = []
        all_removed:  list[dict] = []
        next_cursor = cursor

        while True:
            body: dict = {"access_token": access_token}
            if next_cursor:
                body["cursor"] = next_cursor
            sync_resp      = _plaid_post("/transactions/sync", body, client_id, secret)
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
            self._upsert_plaid_connection(
                source_connection_id=source_connection_id,
                plaid_item_id=plaid_item_id,
                institution_name=institution_name,
                cursor=next_cursor,
            )

        for account in accounts:
            try:
                self._write_account_to_pipeline(source_connection_id, person_id, account, embed)
                stats["accounts_stored"] += 1
            except Exception as e:
                log_lines.append(_log_entry("error", f"Account {account.get('account_id')}: {e}"))
                stats["errors"] += 1

        for txn in all_added:
            try:
                self._write_transaction_to_pipeline(source_connection_id, person_id, txn, "create", embed)
                stats["added"] += 1
            except Exception as e:
                log_lines.append(_log_entry("error", f"Add txn {txn.get('transaction_id')}: {e}"))
                stats["errors"] += 1

        for txn in all_modified:
            try:
                self._write_transaction_to_pipeline(source_connection_id, person_id, txn, "update", embed)
                stats["modified"] += 1
            except Exception as e:
                log_lines.append(_log_entry("error", f"Modify txn {txn.get('transaction_id')}: {e}"))
                stats["errors"] += 1

        for removed in all_removed:
            plaid_txn_id = removed.get("transaction_id")
            if not plaid_txn_id:
                continue
            try:
                self._delete_transaction_in_pipeline(source_connection_id, person_id, plaid_txn_id, embed)
                stats["removed"] += 1
            except Exception as e:
                log_lines.append(_log_entry("error", f"Remove txn {plaid_txn_id}: {e}"))
                stats["errors"] += 1

    # ── Main entry point ──────────────────────────────────────────────────

    def run(self, source_connection: dict, existing_run_id: str | None = None) -> None:
        """Execute a Plaid sync for the given integration source_connection.

        When `existing_run_id` is supplied the scheduler is servicing an adhoc
        run already created by the API; the run row is updated in-place and
        next_run_at is NOT advanced.  Otherwise a new scheduled run row is
        created and the cron schedule is advanced on completion.
        """
        connection_id   = source_connection["id"]
        person_id       = source_connection.get("personId")
        cron_expression = source_connection.get("syncSchedule")
        is_adhoc        = existing_run_id is not None

        if not person_id:
            raise ValueError("plaid_poll connections must have personId")

        log_lines: list[dict] = []
        stats = {"added": 0, "modified": 0, "removed": 0, "accounts_stored": 0, "errors": 0}
        terminal_status = "running"
        run_id: str | None = existing_run_id

        try:
            if is_adhoc:
                log_lines.append(_log_entry("info", "Starting Plaid integration sync (adhoc)"))
                print(f"[plaid_poll] connection {connection_id}: executing adhoc run {run_id}")
            else:
                run_id = self._create_scheduled_run(connection_id)
                log_lines.append(_log_entry("info", "Starting Plaid integration sync"))
                print(f"[plaid_poll] connection {connection_id}: started scheduled run {run_id}")

            client_id, secret = self._fetch_plaid_creds(connection_id)

            items = self._list_plaid_items(connection_id)
            if not items:
                log_lines.append(_log_entry("warn", "No linked bank items found — nothing to sync"))
                terminal_status = "success"
                return

            log_lines.append(_log_entry("info", f"Found {len(items)} linked bank(s)"))

            for item in items:
                try:
                    self._sync_item(connection_id, person_id, item, client_id, secret, stats, log_lines)
                except Exception as e:
                    log_lines.append(_log_entry("error", f"Item {item.get('plaidItemId')} failed: {e}"))
                    stats["errors"] += 1

            log_lines.append(_log_entry(
                "info",
                f"Sync complete: +{stats['added']} ~{stats['modified']} -{stats['removed']} "
                f"across {stats['accounts_stored']} account(s)",
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
            if not is_adhoc and run_id is not None:
                self._advance_next_run(connection_id, cron_expression)
            print(f"[plaid_poll] connection {connection_id}: status={terminal_status} stats={stats}")
