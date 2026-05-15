import hashlib
import json
import os
import uuid
from datetime import datetime, timedelta, timezone

import httpx

from handlers.base import BaseHandler

_PLAID_CLIENT_ID = os.environ.get("PLAID_CLIENT_ID", "")
_PLAID_SECRET = os.environ.get("PLAID_SECRET", "")
_PLAID_ENV = os.environ.get("PLAID_ENV", "sandbox")

_PLAID_BASE = {
    "production":  "https://production.plaid.com",
    "development": "https://development.plaid.com",
}.get(_PLAID_ENV, "https://sandbox.plaid.com")


def _stable_id(prefix: str, key: str) -> str:
    """Deterministic UUID matching Java UUID.nameUUIDFromBytes (MD5, UUID v3 variant)."""
    h = bytearray(hashlib.md5(f"{prefix}:{key}".encode("utf-8")).digest())
    h[6] = (h[6] & 0x0F) | 0x30  # version 3
    h[8] = (h[8] & 0x3F) | 0x80  # variant RFC 4122
    return str(uuid.UUID(bytes=bytes(h)))


def _plaid_post(path: str, body: dict) -> dict:
    body = {**body, "client_id": _PLAID_CLIENT_ID, "secret": _PLAID_SECRET}
    resp = httpx.post(f"{_PLAID_BASE}{path}", json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


class PlaidPollHandler(BaseHandler):
    def __init__(self, http: httpx.Client):
        self.http = http
        self._plaid_poll_source_type_id: str | None = None
        self._finance_domain_id: str | None = None
        self._plaid_connection_schema_id: str | None = None
        self._transaction_schema_id: str | None = None
        self._bank_account_schema_id: str | None = None

    # ── Reference data helpers (lazy, cached) ──────────────────────────────

    def _get_source_type_id(self, name: str) -> str:
        resp = self.http.get("/api/v1/reference/source-types")
        resp.raise_for_status()
        match = next((st for st in resp.json().get("items", []) if st["name"] == name), None)
        if not match:
            raise RuntimeError(f"{name} source type not found")
        return match["id"]

    def _get_finance_domain_id(self) -> str:
        if self._finance_domain_id is None:
            resp = self.http.get("/api/v1/reference/domains")
            resp.raise_for_status()
            match = next((d for d in resp.json().get("items", []) if d["name"] == "finance"), None)
            if not match:
                raise RuntimeError("finance domain not found")
            self._finance_domain_id = match["id"]
        return self._finance_domain_id

    def _get_schema_id(self, entity_type: str) -> str:
        resp = self.http.get(
            "/api/v1/schemas/current",
            params={"domainId": self._get_finance_domain_id(), "entityType": entity_type},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _plaid_poll_source_type(self) -> str:
        if self._plaid_poll_source_type_id is None:
            self._plaid_poll_source_type_id = self._get_source_type_id("plaid_poll")
        return self._plaid_poll_source_type_id

    def _connection_schema(self) -> str:
        if self._plaid_connection_schema_id is None:
            self._plaid_connection_schema_id = self._get_schema_id("plaid_connection")
        return self._plaid_connection_schema_id

    def _transaction_schema(self) -> str:
        if self._transaction_schema_id is None:
            self._transaction_schema_id = self._get_schema_id("transaction")
        return self._transaction_schema_id

    def _bank_account_schema(self) -> str:
        if self._bank_account_schema_id is None:
            self._bank_account_schema_id = self._get_schema_id("bank_account")
        return self._bank_account_schema_id

    # ── Document helper ───────────────────────────────────────────────────

    def _create_document(self, person_id: str, content: str) -> str:
        from embed import embed
        resp = self.http.post("/api/v1/documents", json={
            "personId": person_id,
            "contentText": content,
            "sourceTypeId": self._plaid_poll_source_type(),
            "embedding": embed(content),
            "files": [],
            "supersedesIds": [],
        })
        resp.raise_for_status()
        return resp.json()["id"]

    # ── Fact helpers ──────────────────────────────────────────────────────

    def _create_fact(self, document_id: str, schema_id: str, instance_id: str,
                     operation: str, fields: dict) -> None:
        from embed import embed
        embedding = embed(json.dumps(fields, sort_keys=True)) if fields else []
        resp = self.http.post("/api/v1/facts", json={
            "documentId": document_id,
            "schemaId": schema_id,
            "entityInstanceId": instance_id,
            "operationType": operation,
            "fields": fields,
            "embedding": embedding,
        })
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: failed to store fact ({operation}): {resp.status_code} {resp.text[:200]}")

    # ── Scheduler housekeeping ────────────────────────────────────────────

    def _record_run(self, job_id: str, status: str, detail: str) -> None:
        resp = self.http.post(f"/api/v1/scheduled-jobs/{job_id}/runs", json={
            "status": status,
            "statusDetail": detail,
        })
        if not resp.is_success:
            print(f"[plaid_poll] WARNING: failed to record run: {resp.status_code}")

    def _advance_next_run(self, job_id: str, job: dict) -> None:
        from croniter import croniter
        from zoneinfo import ZoneInfo
        tz = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))
        try:
            cron = croniter(job["cronExpression"], datetime.now(tz))
            next_run = cron.get_next(datetime).astimezone(timezone.utc)
        except Exception:
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)
        self.http.patch(f"/api/v1/scheduled-jobs/{job_id}", json={
            "nextRunAt": next_run.isoformat(),
        })

    # ── Main run method ───────────────────────────────────────────────────

    def run(self, job: dict) -> None:
        job_id = job["id"]
        person_id = job.get("personId")
        assert person_id, "plaid_poll jobs must have personId"

        connections_synced = 0
        errors: list[str] = []

        try:
            connections_resp = self.http.get(
                "/api/v1/facts/current",
                params={"personId": person_id, "entityType": "plaid_connection", "limit": 50},
            )
            connections_resp.raise_for_status()
            connections = connections_resp.json().get("items", [])

            if not connections:
                self._record_run(job_id, "skipped", "No Plaid connections found for this person")
                self._advance_next_run(job_id, job)
                return

            for connection in connections:
                fields = connection.get("fields", {})
                item_id = fields.get("item_id", "")
                access_token = fields.get("access_token", "")
                cursor = fields.get("sync_cursor") or None
                connection_instance_id = connection["entityInstanceId"]

                if not access_token:
                    errors.append(f"connection {item_id}: missing access_token")
                    continue

                try:
                    self._sync_one_connection(
                        person_id=person_id,
                        item_id=item_id,
                        access_token=access_token,
                        cursor=cursor,
                        connection_instance_id=connection_instance_id,
                        connection_doc_id=connection.get("documentId", ""),
                    )
                    connections_synced += 1
                except Exception as e:
                    errors.append(f"connection {item_id}: {e}")
                    print(f"[plaid_poll] error syncing {item_id}: {e}")

        except Exception as e:
            errors.append(str(e))
            print(f"[plaid_poll] fatal error: {e}")

        status = "success" if not errors else ("failure" if connections_synced == 0 else "skipped")
        detail = f"Synced {connections_synced} connection(s)" + (f"; errors: {'; '.join(errors)}" if errors else "")
        self._record_run(job_id, status, detail)
        self._advance_next_run(job_id, job)

    def _sync_one_connection(
        self,
        person_id: str,
        item_id: str,
        access_token: str,
        cursor: str | None,
        connection_instance_id: str,
        connection_doc_id: str,
    ) -> None:
        all_added: list[dict] = []
        all_modified: list[dict] = []
        all_removed: list[dict] = []
        next_cursor = cursor

        while True:
            body = {"access_token": access_token}
            if next_cursor:
                body["cursor"] = next_cursor
            sync_resp = _plaid_post("/transactions/sync", body)
            all_added.extend(sync_resp.get("added", []))
            all_modified.extend(sync_resp.get("modified", []))
            all_removed.extend(sync_resp.get("removed", []))
            next_cursor = sync_resp.get("next_cursor", "")
            if not sync_resp.get("has_more", False):
                break

        accounts_resp = _plaid_post("/accounts/get", {"access_token": access_token})
        accounts = accounts_resp.get("accounts", [])

        now_str = datetime.now(timezone.utc).isoformat()
        doc_id = self._create_document(
            person_id,
            f"Plaid sync for item {item_id} at {now_str}: "
            f"{len(all_added)} added, {len(all_modified)} modified, {len(all_removed)} removed transactions; "
            f"{len(accounts)} accounts updated",
        )

        for txn in all_added:
            cat = txn.get("personal_finance_category") or {}
            category_str = " > ".join(filter(None, [cat.get("primary"), cat.get("detailed")])) or ""
            self._create_fact(doc_id, self._transaction_schema(),
                              _stable_id("plaid:transaction", txn["transaction_id"]),
                              "create", {
                                  "transaction_id":    txn["transaction_id"],
                                  "account_id":        txn["account_id"],
                                  "amount":            txn["amount"],
                                  "iso_currency_code": txn.get("iso_currency_code"),
                                  "merchant_name":     txn.get("merchant_name"),
                                  "name":              txn["name"],
                                  "category":          category_str,
                                  "date":              txn["date"],
                                  "pending":           txn.get("pending", False),
                              })

        for txn in all_modified:
            cat = txn.get("personal_finance_category") or {}
            category_str = " > ".join(filter(None, [cat.get("primary"), cat.get("detailed")])) or ""
            self._create_fact(doc_id, self._transaction_schema(),
                              _stable_id("plaid:transaction", txn["transaction_id"]),
                              "update", {
                                  "amount":        txn["amount"],
                                  "merchant_name": txn.get("merchant_name"),
                                  "name":          txn["name"],
                                  "category":      category_str,
                                  "date":          txn["date"],
                                  "pending":       txn.get("pending", False),
                              })

        for removed in all_removed:
            self._create_fact(doc_id, self._transaction_schema(),
                              _stable_id("plaid:transaction", removed["transaction_id"]),
                              "delete", {})

        for account in accounts:
            balances = account.get("balances", {})
            self._create_fact(doc_id, self._bank_account_schema(),
                              _stable_id("plaid:account", account["account_id"]),
                              "update", {
                                  "account_id":        account["account_id"],
                                  "item_id":           item_id,
                                  "name":              account["name"],
                                  "official_name":     account.get("official_name"),
                                  "type":              account["type"],
                                  "subtype":           account.get("subtype"),
                                  "mask":              account.get("mask"),
                                  "current_balance":   balances.get("current"),
                                  "available_balance": balances.get("available"),
                                  "iso_currency_code": balances.get("iso_currency_code"),
                              })

        self._create_fact(doc_id, self._connection_schema(),
                          connection_instance_id,
                          "update", {
                              "sync_cursor":    next_cursor,
                              "last_synced_at": now_str,
                          })
