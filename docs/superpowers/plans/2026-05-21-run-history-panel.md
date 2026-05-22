# Run History Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-run RunStrip on each connection card with an expandable history panel showing upcoming, queued, and past runs; enforce a 20-run DB retention limit.

**Architecture:** Backend adds a retention DELETE inside `SyncRunRepository.patch()` after every terminal-status update. Frontend replaces the parent-level `fetchLatestRuns` pre-fetch with per-card lazy fetching of the full run list, then renders three conditional sections (Upcoming / Queued / History) inside a new `RunHistoryStrip` component.

**Tech Stack:** Scala 3 / zio-jdbc (backend), React 18 / TypeScript (frontend). No new endpoints, no schema changes.

---

## Files

| Action | Path | What changes |
|---|---|---|
| Modify | `backend/http_server/src/main/scala/com/myassistant/db/repositories/SyncRunRepository.scala` | Add retention DELETE inside `patch()` |
| Modify | `frontend/src/api.ts` | Add `fetchRuns()`, keep `fetchLatestRuns` / `fetchRunDetail` unchanged |
| Modify | `frontend/src/components/SourceConnectionsList.tsx` | Replace `RunStrip` → `RunHistoryStrip`; remove parent `runsMap` / `fetchLatestRuns` effect |

---

## Task 1 — Backend: 20-run retention in `SyncRunRepository.patch()`

**Files:**
- Modify: `backend/http_server/src/main/scala/com/myassistant/db/repositories/SyncRunRepository.scala` — `patch()` method, lines ~150–180

**Context:** `patch()` currently runs one SQL statement inside `transaction(...)`. zio-jdbc's `transaction` accepts any `ZIO[ZConnection, E, A]`, so we can chain a second query inside the same block using a `for` comprehension.

- [ ] **Step 1: Add the retention DELETE inside `patch()`**

Open `SyncRunRepository.scala`. Find the `patch()` method in `SyncRunRepository.Live`. Replace its body with:

```scala
def patch(
    runId:              UUID,
    sourceConnectionId: UUID,
    req:                PatchSyncRunRequest,
): ZIO[ZConnectionPool, AppError, Option[SyncRun]] =
  val assignments: List[SqlFragment] = List.concat(
    req.status.map(s => sql"status = $s"),
    req.completedAt.map(ts => sql"completed_at = ${java.sql.Timestamp.from(ts)}"),
    req.stats.map(s => sql"stats = ${s.asJson.noSpaces}::jsonb"),
    req.logLines.map(l => sql"log_lines = ${l.noSpaces}::jsonb"),
  )
  if assignments.isEmpty then
    findById(runId).map(_.filter(_.sourceConnectionId == sourceConnectionId))
  else
    val setFrag = assignments.reduce(_ ++ SqlFragment(", ") ++ _)
    val updateQ = sql"UPDATE sync_runs SET " ++ setFrag ++
                  sql" WHERE id = ${runId.toString}::uuid " ++
                  sql"   AND source_connection_id = ${sourceConnectionId.toString}::uuid " ++
                  sql" RETURNING " ++ runCols
    val isTerminal = req.status.exists(s => Set("success", "warning", "failed").contains(s))
    val retentionQ =
      sql"""DELETE FROM sync_runs
            WHERE source_connection_id = ${sourceConnectionId.toString}::uuid
              AND id NOT IN (
                SELECT id FROM sync_runs
                WHERE source_connection_id = ${sourceConnectionId.toString}::uuid
                ORDER BY started_at DESC
                LIMIT 20
              )"""
    transaction {
      for
        row <- updateQ.query[RunRow].selectOne
        _   <- ZIO.when(isTerminal)(retentionQ.update)
      yield row
    }.mapError(mapSqlError)
     .map(_.map(rowToRun))
```

- [ ] **Step 2: Compile to verify no errors**

```bash
cd backend/http_server && sbt compile 2>&1 | tail -20
```

Expected: `[success] Total time: ...` — no compile errors.

- [ ] **Step 3: Manually verify retention fires**

With the server running, trigger more than 20 adhoc syncs on one connection, then query:

```bash
PGPASSWORD=changeme psql -U myassistant -d myassistant -h localhost \
  -c "SELECT COUNT(*) FROM sync_runs WHERE source_connection_id = '<your-conn-id>'::uuid;"
```

Expected: `<= 20`.

- [ ] **Step 4: Commit**

```bash
git add backend/http_server/src/main/scala/com/myassistant/db/repositories/SyncRunRepository.scala
git commit -m "feat(db): prune sync_runs to 20 per connection on terminal patch"
```

---

## Task 2 — Frontend: add `fetchRuns` to `api.ts`

**Files:**
- Modify: `frontend/src/api.ts`

- [ ] **Step 1: Add `fetchRuns` function**

Open `frontend/src/api.ts`. After the `fetchLatestRuns` function, add:

```typescript
export async function fetchRuns(connId: string, limit = 20): Promise<SyncRun[]> {
  const resp = await fetch(`/api/v1/source-connections/${connId}/runs?limit=${limit}`)
  if (!resp.ok) throw new Error(`fetch runs failed: ${resp.status}`)
  const data = await resp.json()
  return data.items ?? []
}
```

- [ ] **Step 2: Verify the API response shape**

```bash
curl -s -H "Authorization: Bearer dev-token-change-me-in-production" \
  "http://localhost:8080/api/v1/source-connections/<your-conn-id>/runs?limit=5" | python3 -m json.tool | head -30
```

Expected: `{ "items": [ { "id": "...", "runType": "...", "status": "...", ... } ] }`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api.ts
git commit -m "feat(ui): add fetchRuns API helper"
```

---

## Task 3 — Frontend: `RunHistoryStrip` component

**Files:**
- Modify: `frontend/src/components/SourceConnectionsList.tsx`

This task replaces `RunStrip` entirely and removes the parent-level `runsMap` / `fetchLatestRuns` effect.

### Step-by-step

- [ ] **Step 1: Extend `RunStatusBadge` to handle `'queued'` and `'upcoming'`**

Find `RunStatusBadge` in `SourceConnectionsList.tsx` (~line 79). Replace its full implementation:

```tsx
function RunStatusBadge({ status }: { status: SyncRun['status'] | 'queued' | 'upcoming' }) {
  let bg: string = T.successBg
  let border: string = T.successBorder
  let color: string = T.successText
  let label = 'success'

  if (status === 'warning') {
    bg = T.warningBg; border = T.warningBorder; color = T.warningText; label = 'warning'
  } else if (status === 'failed') {
    bg = T.errorBg; border = T.errorBorder; color = T.errorText; label = 'failed'
  } else if (status === 'running') {
    bg = T.infoBg; border = T.infoBorder; color = T.infoText; label = 'running'
  } else if (status === 'queued') {
    bg = 'rgba(56,189,248,0.15)'; border = 'rgba(56,189,248,0.3)'; color = '#38bdf8'; label = 'queued'
  } else if (status === 'upcoming') {
    bg = T.accentTint; border = T.accentBorder; color = T.accentLight; label = 'upcoming'
  }

  return (
    <span style={{
      background: bg,
      border: `1px solid ${border}`,
      color,
      borderRadius: 99,
      padding: '2px 6px',
      fontSize: 10,
      fontWeight: 600,
    }}>
      {label}
    </span>
  )
}
```

- [ ] **Step 2: Add `statsStr` helper at module level (extract from `RunStrip`)**

Below `RunStatusBadge`, add:

```tsx
function statsStr(stats: Record<string, number>): string {
  const parts: string[] = []
  if (stats.added !== undefined) parts.push(`+${stats.added} added`)
  if (stats.modified !== undefined) parts.push(`~${stats.modified} modified`)
  if (stats.removed !== undefined) parts.push(`-${stats.removed} removed`)
  if (stats.errors !== undefined && stats.errors > 0) parts.push(`${stats.errors} errors`)
  return parts.join('  ')
}
```

- [ ] **Step 3: Add `SectionLabel` helper component**

```tsx
function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      padding: '4px 18px 2px 70px',
      background: T.bgRunStrip,
      borderTop: `1px solid ${T.borderRun}`,
    }}>
      <span style={{
        color: T.textVeryMuted,
        fontSize: 9,
        textTransform: 'uppercase' as const,
        letterSpacing: '0.5px',
        fontWeight: 600,
      }}>
        {children}
      </span>
    </div>
  )
}
```

- [ ] **Step 4: Add `HistoryRow` helper component**

```tsx
interface HistoryRowProps {
  runType: string
  status: SyncRun['status'] | 'queued' | 'upcoming'
  time: string          // ISO — formatRelative handles past and future
  stats?: Record<string, number> | null
  note?: string
  onViewLogs?: () => void
}

function HistoryRow({ runType, status, time, stats, note, onViewLogs }: HistoryRowProps) {
  return (
    <div style={{
      padding: '5px 18px 5px 70px',
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      borderTop: `1px solid ${T.borderRun}`,
      background: T.bgRunStrip,
    }}>
      <span style={{
        color: T.textVeryMuted,
        fontSize: 9,
        width: 56,
        flexShrink: 0,
      }}>
        {runType === 're_extract' ? 're-extract' : runType}
      </span>
      <RunStatusBadge status={status} />
      <span style={{ color: T.textMuted, fontSize: 11 }}>
        {formatRelative(time)}
      </span>
      {stats && (
        <span style={{ color: T.textSecondary, fontSize: 11 }}>
          {statsStr(stats)}
        </span>
      )}
      {note && (
        <span style={{ color: T.textVeryMuted, fontSize: 10, fontStyle: 'italic' }}>
          {note}
        </span>
      )}
      <div style={{ flex: 1 }} />
      {onViewLogs && (
        <button
          onClick={onViewLogs}
          style={{
            background: 'rgba(99,102,241,0.1)',
            border: `1px solid rgba(99,102,241,0.25)`,
            color: T.accentLight,
            borderRadius: 6,
            padding: '3px 10px',
            fontSize: 10,
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          Logs →
        </button>
      )}
    </div>
  )
}
```

- [ ] **Step 5: Add `RunHistoryStrip` component — replacing `RunStrip`**

Replace the entire `RunStrip` function and its `RunStripProps` interface with:

```tsx
interface RunHistoryStripProps {
  conn: SourceConnection
  onViewLogs: (runId: string) => void
}

function RunHistoryStrip({ conn, onViewLogs }: RunHistoryStripProps) {
  const [expanded, setExpanded] = useState(false)
  const [runs, setRuns] = useState<SyncRun[] | null>(null)
  const [loadingRuns, setLoadingRuns] = useState(true)

  useEffect(() => {
    fetchRuns(conn.id)
      .then(setRuns)
      .catch(() => setRuns([]))
      .finally(() => setLoadingRuns(false))
  }, [conn.id])

  // Most salient run for the collapsed strip (priority: queued > in-progress > latest completed)
  const queuedRuns  = runs?.filter(r => r.status === 'running' && !r.completedAt) ?? []
  const historyRuns = runs?.filter(r => r.status !== 'running') ?? []
  const stripRun    = queuedRuns[0] ?? runs?.find(r => r.status === 'running') ?? historyRuns[0] ?? null

  const stripStatus: SyncRun['status'] | 'queued' =
    stripRun && queuedRuns.includes(stripRun) ? 'queued' : (stripRun?.status ?? 'success')

  return (
    <div style={{ borderRadius: '0 0 12px 12px', overflow: 'hidden' }}>
      {/* ── Collapsed strip ─────────────────────────────────── */}
      <div style={{
        background: T.bgRunStrip,
        borderTop: `1px solid ${T.borderRun}`,
        padding: '9px 18px 9px 70px',
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        minHeight: 36,
      }}>
        {loadingRuns && (
          <span style={{ color: T.textVeryMuted, fontSize: 11 }}>Loading runs...</span>
        )}
        {!loadingRuns && !stripRun && (
          <span style={{ color: T.textVeryMuted, fontSize: 11 }}>No sync runs yet</span>
        )}
        {!loadingRuns && stripRun && (
          <>
            <span style={{
              background: T.border,
              color: T.textSecondary,
              borderRadius: 99,
              padding: '2px 6px',
              fontSize: 10,
              fontWeight: 500,
            }}>
              {stripRun.runType === 're_extract' ? 're-extract' : stripRun.runType}
            </span>
            <RunStatusBadge status={stripStatus} />
            <span style={{ color: T.textMuted, fontSize: 11 }}>
              {formatRelative(stripRun.startedAt)}
            </span>
            {stripRun.stats && stripStatus !== 'queued' && (
              <span style={{ color: T.textSecondary, fontSize: 11 }}>
                {statsStr(stripRun.stats)}
              </span>
            )}
          </>
        )}
        <div style={{ flex: 1 }} />
        <button
          onClick={() => setExpanded(e => !e)}
          style={{
            background: 'rgba(99,102,241,0.1)',
            border: `1px solid rgba(99,102,241,0.25)`,
            color: T.accentLight,
            borderRadius: 6,
            padding: '3px 10px',
            fontSize: 10,
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          History {expanded ? '▴' : '▾'}
        </button>
      </div>

      {/* ── Expanded panel ──────────────────────────────────── */}
      {expanded && (
        <>
          {/* Upcoming */}
          {conn.syncScheduled && conn.nextRunAt && (
            <>
              <SectionLabel>Upcoming</SectionLabel>
              <HistoryRow
                runType="scheduled"
                status="upcoming"
                time={conn.nextRunAt}
              />
            </>
          )}

          {/* Queued */}
          {queuedRuns.length > 0 && (
            <>
              <SectionLabel>Queued</SectionLabel>
              {queuedRuns.map(r => (
                <HistoryRow
                  key={r.id}
                  runType={r.runType}
                  status="queued"
                  time={r.startedAt}
                  note="pending scheduler pickup"
                />
              ))}
            </>
          )}

          {/* History */}
          <SectionLabel>History</SectionLabel>
          {historyRuns.length === 0 && (
            <div style={{
              padding: '8px 18px 8px 70px',
              background: T.bgRunStrip,
              borderTop: `1px solid ${T.borderRun}`,
              color: T.textVeryMuted,
              fontSize: 11,
            }}>
              No runs yet.
            </div>
          )}
          {historyRuns.map(r => (
            <HistoryRow
              key={r.id}
              runType={r.runType}
              status={r.status}
              time={r.startedAt}
              stats={r.stats}
              onViewLogs={() => onViewLogs(r.id)}
            />
          ))}
        </>
      )}
    </div>
  )
}
```

- [ ] **Step 6: Update `ConnectionCard` to use `RunHistoryStrip`**

Find the `ConnectionCard` component. Remove the `latestRuns`, `loadingRuns` props from `CardProps` and the component signature. Replace the `<RunStrip ... />` call with `<RunHistoryStrip ... />`:

```tsx
interface CardProps {
  conn: SourceConnection
  onEdit: (id: string) => void
  onRefresh: () => void
  onViewLogs: (connId: string, runId: string) => void
}

function ConnectionCard({ conn, onEdit, onRefresh, onViewLogs }: CardProps) {
  // ... keep all existing state (syncing, deleting) and handlers unchanged ...

  return (
    <div style={{
      background: T.bgCard,
      border: `1px solid ${T.border}`,
      borderRadius: 12,
      marginBottom: 12,
    }}>
      {/* Card body — unchanged */}
      <div style={{ padding: '14px 18px', display: 'flex', gap: 14, alignItems: 'flex-start' }}>
        {/* ... all existing card body JSX unchanged ... */}
      </div>

      {/* Replace RunStrip with RunHistoryStrip */}
      <RunHistoryStrip
        conn={conn}
        onViewLogs={(runId) => onViewLogs(conn.id, runId)}
      />
    </div>
  )
}
```

- [ ] **Step 7: Clean up `SourceConnectionsList` — remove parent pre-fetch**

In the `SourceConnectionsList` component, remove:
- `runsMap` state: `const [runsMap, setRunsMap] = useState<Record<string, LatestRuns>>({})`
- `loadingRuns` state: `const [loadingRuns, setLoadingRuns] = useState(false)`
- The `connIds` memo and the `useEffect` that calls `fetchLatestRuns`

Update the `ConnectionCard` render to remove the `latestRuns` and `loadingRuns` props:

```tsx
{!loading && connections.map(conn => (
  <ConnectionCard
    key={conn.id}
    conn={conn}
    onEdit={onEdit}
    onRefresh={onRefresh}
    onViewLogs={(connId, runId) => setLogModal({ connId, runId })}
  />
))}
```

- [ ] **Step 8: Remove unused import `fetchLatestRuns` from the import line**

```tsx
import { triggerAdhocSync, deleteSourceConnection, fetchRuns, fetchRunDetail } from '../api'
import type { SourceConnection, SyncRun } from '../types'
```

(Remove `LatestRuns` from the types import too since it's no longer used in this file.)

- [ ] **Step 9: Check TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Expected: no errors.

- [ ] **Step 10: Run the app and verify visually**

```bash
# Ensure backend is running, then:
cd frontend && npm run dev
```

Open the app, navigate to the Source Connections tab. Verify:
- Each card shows collapsed strip with most-salient run populated on load
- Clicking "History ▾" expands the panel
- **Upcoming** row appears for scheduled connections with `nextRunAt` set
- **Queued** rows appear for connections with `status=running, completedAt=null` runs
- **History** rows appear with `Logs →` buttons that open the log modal
- Clicking "History ▴" collapses the panel

- [ ] **Step 11: Commit**

```bash
git add frontend/src/components/SourceConnectionsList.tsx frontend/src/api.ts
git commit -m "feat(ui): run history panel with upcoming, queued, and history sections"
```

---

## Self-review checklist (completed)

- ✅ **Spec coverage:** Retention (Task 1), `fetchRuns` (Task 2), collapsed strip, Upcoming/Queued/History sections, badge colours, empty state, LogModal unchanged (Task 3)
- ✅ **No placeholders:** All code blocks are complete
- ✅ **Type consistency:** `RunStatusBadge` extended to `'queued' | 'upcoming'` in Step 1 before it's used in Step 4/5; `statsStr` extracted in Step 2 before used in Step 4/5; `fetchRuns` imported in Step 8
- ✅ **`HistoryRowProps.status`** uses the same extended union as `RunStatusBadge`
