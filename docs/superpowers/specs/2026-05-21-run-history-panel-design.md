# Run History Panel — Design Spec

**Date:** 2026-05-21  
**Status:** Approved

---

## Overview

Replace the current single-run `RunStrip` in `SourceConnectionsList` with an expandable history panel that shows upcoming scheduled runs, queued adhoc runs, and a paginated run history. Add a 20-run retention limit enforced at the DB layer.

---

## Layout: Expandable Strip (Option A)

The bottom strip of each connection card stays collapsed by default, showing the most salient current state. Clicking "History ▾" expands an inline panel below it. Clicking "History ▴" collapses it.

### Collapsed strip (unchanged appearance)

Shows the single most salient run, priority order:
1. Queued adhoc run (if any `status=running, completedAt=null`)
2. In-progress run (if any `status=running, completedAt!=null`)
3. Most recent completed run

### Expanded panel — three sections

Sections are conditionally rendered; a panel can show 1, 2, or all 3.

#### Upcoming *(only when `syncScheduled=true` and `nextRunAt` is set)*

A single synthetic row derived from `conn.nextRunAt` — no extra API call.

| Field | Value |
|---|---|
| Type label | `scheduled` |
| Status badge | `upcoming` (indigo) |
| Time | relative: `in 22h` |
| No Logs button | — |

#### Queued *(only when there are runs with `status=running` and `completedAt=null`)*

One row per queued run (typically 0–1). These are adhoc runs created by "Sync Now" awaiting scheduler pickup.

| Field | Value |
|---|---|
| Type label | `adhoc` |
| Status badge | `queued` (sky blue) |
| Time | relative: `just now` |
| Note | `pending scheduler pickup` |
| No Logs button | — (no log lines yet) |

#### History *(always shown)*

Last 20 completed/failed runs fetched from `GET /api/v1/source-connections/{id}/runs?limit=20` on first expand (lazy). Excludes any `status=running` rows (those appear in Queued above).

Each row:

| Field | Value |
|---|---|
| Type label | `adhoc` / `scheduled` |
| Status badge | `success` / `warning` / `failed` |
| Time | relative: `1m ago` |
| Stats | `+24 added` / `2 errors` (if present) |
| Logs button | `Logs →` — opens existing `LogModal` |

Empty state: `No runs yet.`

---

## Data Sources

| Data | Source | Fetched when |
|---|---|---|
| `conn.nextRunAt` | Already on `SourceConnection` | Always available |
| Queued runs | `GET /runs?limit=20` response, filtered to `status=running` | On first expand |
| History runs | Same response, filtered to terminal status | On first expand |

Single API call on expand. No polling — user can collapse/re-expand to refresh.

---

## 20-Run Retention Policy

After each run is patched to a terminal status (`success`, `warning`, `failed`), the `SyncRunRepository.patch()` method executes a cleanup query:

```sql
DELETE FROM sync_runs
WHERE source_connection_id = $id
  AND id NOT IN (
    SELECT id FROM sync_runs
    WHERE source_connection_id = $id
    ORDER BY started_at DESC
    LIMIT 20
  )
```

This runs in the same transaction as the PATCH. No cron job, no separate migration. Existing rows beyond 20 are pruned naturally as new runs complete.

---

## Frontend Changes — `SourceConnectionsList.tsx`

### New API function

Add `fetchRuns(connId, limit=20)` to `api.ts` calling `GET /api/v1/source-connections/{id}/runs?limit=20`. Returns `SyncRun[]`.

### Replace `RunStrip` → `RunHistoryStrip`

New component owns:
- `expanded: boolean` state (default `false`)
- `runs: SyncRun[] | null` state (null = loading)
- `loadingRuns: boolean` state

Runs are fetched on **card mount** via `fetchRuns`, replacing the parent's `fetchLatestRuns` pre-fetch. This keeps the collapsed strip populated on load while also having the full list ready for expand.

Collapsed render: derives most-salient run from the fetched list (same priority order as before). Toggle button changes from `View Logs →` to `History ▾` / `History ▴`.

Expanded render: three conditional sections as described above.

### Remove `fetchLatestRuns` calls from parent

`SourceConnectionsList` currently pre-fetches latest runs for all cards on mount via `fetchLatestRuns`. Remove the `runsMap` state and that effect entirely — each `RunHistoryStrip` now fetches its own `?limit=20` list independently on mount. Net API call count is unchanged (N cards → N calls either way), but each card fetches richer data in one call instead of two.

### Keep `LogModal` unchanged

`Logs →` in each history row passes `(connId, runId)` to the existing `setLogModal` callback. No changes to `LogModal`.

---

## Backend Changes — `SyncRunRepository`

Add pruning to the `patch()` method in `SyncRunRepository.Live`:

After the `UPDATE sync_runs ... RETURNING` query succeeds and the run's new status is terminal (`success`, `warning`, `failed`), execute the retention DELETE in the same `transaction` block.

No new endpoints, no schema changes.

---

## Status Badge Colours

| Status | Background | Text | Where |
|---|---|---|---|
| `upcoming` | indigo 15% | `#818cf8` | Upcoming section only |
| `queued` | sky 15% | `#38bdf8` | Queued section only |
| `success` | green 15% | `#4ade80` | History |
| `warning` | amber 15% | `#fbbf24` | History |
| `failed` | red 15% | `#f87171` | History |
| `running` | blue 15% | `#60a5fa` | History (active run briefly visible) |

---

## Out of Scope

- Auto-refresh / polling while expanded
- Pagination beyond 20 runs
- Filtering history by run type
- Per-run cancel button
