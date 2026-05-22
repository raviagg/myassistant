import { useState, useEffect } from 'react'
import { T } from '../theme'
import { triggerAdhocSync, deleteSourceConnection, fetchRuns, fetchRunDetail } from '../api'
import type { SourceConnection, SyncRun } from '../types'

// ── Relative time helpers ────────────────────────────────────────────────────

function formatDuration(ms: number): string {
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}

function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  if (diff < 0) return `in ${formatDuration(-diff)}`
  return `${formatDuration(diff)} ago`
}

// ── Source type icon/color config ────────────────────────────────────────────

interface SourceStyle {
  bg: string
  color: string
  icon: string
}

function getSourceStyle(sourceType: string): SourceStyle {
  switch (sourceType) {
    case 'plaid_poll':
      return { bg: 'rgba(0,163,140,0.2)', color: '#00a38c', icon: '🏦' }
    case 'gmail_poll':
      return { bg: 'rgba(220,57,18,0.2)', color: '#dc3912', icon: '📧' }
    case 'news_poll':
      return { bg: 'rgba(251,191,36,0.2)', color: '#fbbf24', icon: '📰' }
    case 'chatbot':
      return { bg: 'rgba(99,102,241,0.2)', color: '#a5b4fc', icon: '💬' }
    default:
      return { bg: 'rgba(148,163,184,0.15)', color: '#94a3b8', icon: '🔌' }
  }
}

// ── Status badge ─────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: SourceConnection['status'] }) {
  let bg: string = T.successBg
  let border: string = T.successBorder
  let color: string = T.successText
  let label = '● active'

  if (status === 'paused') {
    bg = T.warningBg; border = T.warningBorder; color = T.warningText; label = '● paused'
  } else if (status === 'error') {
    bg = T.errorBg; border = T.errorBorder; color = T.errorText; label = '● error'
  }

  return (
    <span style={{
      background: bg,
      border: `1px solid ${border}`,
      color,
      borderRadius: 99,
      padding: '2px 8px',
      fontSize: 11,
      fontWeight: 600,
      whiteSpace: 'nowrap',
    }}>
      {label}
    </span>
  )
}

// ── Run status mini badge ────────────────────────────────────────────────────

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

// ── Stats string helper ──────────────────────────────────────────────────────

function statsStr(stats: Record<string, number>): string {
  const parts: string[] = []
  if (stats.added !== undefined) parts.push(`+${stats.added} added`)
  if (stats.modified !== undefined) parts.push(`~${stats.modified} modified`)
  if (stats.removed !== undefined) parts.push(`-${stats.removed} removed`)
  if (stats.errors !== undefined && stats.errors > 0) parts.push(`${stats.errors} errors`)
  return parts.join('  ')
}

// ── Section label ────────────────────────────────────────────────────────────

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

// ── History row ──────────────────────────────────────────────────────────────

interface HistoryRowProps {
  runType: string
  status: SyncRun['status'] | 'queued' | 'upcoming'
  time: string
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

// ── Run History Strip ────────────────────────────────────────────────────────

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

// ── Log Modal ────────────────────────────────────────────────────────────────

interface LogModalProps {
  connId: string
  runId: string
  onClose: () => void
}

function LogModal({ connId, runId, onClose }: LogModalProps) {
  const [run, setRun] = useState<SyncRun | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    fetchRunDetail(connId, runId)
      .then(setRun)
      .catch(e => setLoadError(e instanceof Error ? e.message : 'Failed to load run'))
  }, [connId, runId])

  const logLevelColor = (level: string) => {
    if (level === 'warn' || level === 'warning') return T.warningText
    if (level === 'error') return T.errorText
    return T.textSecondary
  }

  const formatTime = (iso: string) => {
    try {
      const d = new Date(iso)
      return `[${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}]`
    } catch {
      return '[--:--:--]'
    }
  }

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.6)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
      onClick={e => { if (e.target === e.currentTarget) onClose() }}
    >
      <div style={{
        width: 680,
        maxHeight: '80vh',
        background: T.bgCard,
        border: `1px solid ${T.border}`,
        borderRadius: 14,
        display: 'flex',
        flexDirection: 'column',
      }}>
        {/* Header */}
        <div style={{
          padding: '16px 20px',
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          borderBottom: `1px solid ${T.border}`,
          flexShrink: 0,
        }}>
          <span style={{ fontSize: 15, fontWeight: 700, color: T.textPrimary, flex: 1 }}>Sync Log</span>
          {run && (
            <>
              <span style={{
                background: T.border,
                color: T.textSecondary,
                borderRadius: 99,
                padding: '2px 8px',
                fontSize: 11,
              }}>
                {run.runType}
              </span>
              <RunStatusBadge status={run.status} />
            </>
          )}
          <button
            onClick={onClose}
            style={{
              background: 'transparent',
              border: 'none',
              color: T.textMuted,
              cursor: 'pointer',
              fontSize: 18,
              lineHeight: 1,
              padding: '0 4px',
            }}
          >
            ✕
          </button>
        </div>

        {/* Stats row */}
        {run?.stats && Object.keys(run.stats).length > 0 && (
          <div style={{
            padding: '8px 20px',
            background: T.bgPage,
            borderBottom: `1px solid ${T.border}`,
            display: 'flex',
            gap: 16,
            flexShrink: 0,
            flexWrap: 'wrap',
          }}>
            {(run.stats.added !== undefined) && (
              <span style={{ color: T.successText, fontSize: 12 }}>+{run.stats.added} added</span>
            )}
            {(run.stats.modified !== undefined) && (
              <span style={{ color: T.warningText, fontSize: 12 }}>~{run.stats.modified} modified</span>
            )}
            {(run.stats.removed !== undefined) && (
              <span style={{ color: T.errorText, fontSize: 12 }}>-{run.stats.removed} removed</span>
            )}
            {(run.stats.errors !== undefined) && (
              <span style={{ color: T.errorText, fontSize: 12 }}>{run.stats.errors} errors</span>
            )}
          </div>
        )}

        {/* Log lines */}
        <div style={{ overflowY: 'auto', flex: 1, padding: '12px 20px' }}>
          {loadError && (
            <div style={{ color: T.errorText, fontSize: 13 }}>{loadError}</div>
          )}
          {!run && !loadError && (
            <div style={{ color: T.textMuted, fontSize: 13 }}>Loading...</div>
          )}
          {run && (!run.logLines || run.logLines.length === 0) && (
            <div style={{ color: T.textVeryMuted, fontSize: 12 }}>No log lines available.</div>
          )}
          {run?.logLines?.map((line, i) => (
            <div
              key={i}
              style={{
                fontFamily: "'SF Mono', 'Fira Code', monospace",
                fontSize: 11,
                marginBottom: 3,
                color: logLevelColor(line.level),
              }}
            >
              <span style={{ color: T.textVeryMuted, marginRight: 8 }}>{formatTime(line.time)}</span>
              {line.msg}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

// ── Connection Card ──────────────────────────────────────────────────────────

interface CardProps {
  conn: SourceConnection
  onEdit: (id: string) => void
  onRefresh: () => void
  onViewLogs: (connId: string, runId: string) => void
}

function ConnectionCard({ conn, onEdit, onRefresh, onViewLogs }: CardProps) {
  const [syncing, setSyncing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const src = getSourceStyle(conn.sourceType)

  const handleSync = async () => {
    setSyncing(true)
    try {
      await triggerAdhocSync(conn.id)
      setSyncing(false)
      onRefresh()
    } catch (e) {
      setSyncing(false)
      alert(e instanceof Error ? e.message : 'Sync failed')
    }
  }

  const handleDelete = async () => {
    if (!confirm(`Delete connection "${conn.connectionName}"? This cannot be undone.`)) return
    setDeleting(true)
    try {
      await deleteSourceConnection(conn.id)
      onRefresh()
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Delete failed')
      setDeleting(false)
    }
  }

  return (
    <div style={{
      background: T.bgCard,
      border: `1px solid ${T.border}`,
      borderRadius: 12,
      marginBottom: 12,
    }}>
      {/* Card body */}
      <div style={{ padding: '14px 18px', display: 'flex', gap: 14, alignItems: 'flex-start' }}>
        {/* Icon */}
        <div style={{
          width: 46,
          height: 46,
          borderRadius: 10,
          background: src.bg,
          color: src.color,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 20,
          flexShrink: 0,
        }}>
          {src.icon}
        </div>

        {/* Info */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 2 }}>
            <span style={{ fontSize: 14, fontWeight: 700, color: T.textPrimary }}>
              {conn.connectionName}
            </span>
            <StatusBadge status={conn.status} />
          </div>
          <div style={{ color: T.textMuted, fontSize: 12, marginBottom: 2 }}>
            {conn.sourceType}
          </div>
          <div style={{ color: T.textSecondary, fontSize: 12, marginBottom: 2 }}>
            Last synced:{' '}
            {conn.lastSyncedAt ? formatRelative(conn.lastSyncedAt) : 'never'}
          </div>
          {conn.nextRunAt && (
            <div style={{ color: T.textSecondary, fontSize: 12, marginBottom: 2 }}>
              Next run: {formatRelative(conn.nextRunAt)}
            </div>
          )}

          {/* Action buttons */}
          <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
            {conn.syncAdhoc && conn.status === 'active' && (
              <button
                onClick={handleSync}
                disabled={syncing}
                style={{
                  background: 'transparent',
                  border: `1px solid ${T.border}`,
                  color: T.textSecondary,
                  borderRadius: 6,
                  padding: '3px 10px',
                  fontSize: 11,
                  cursor: syncing ? 'not-allowed' : 'pointer',
                  opacity: syncing ? 0.6 : 1,
                }}
              >
                {syncing ? 'Syncing...' : 'Sync Now'}
              </button>
            )}
            <button
              onClick={() => onEdit(conn.id)}
              disabled={deleting}
              style={{
                background: 'transparent',
                border: `1px solid ${T.border}`,
                color: T.textSecondary,
                borderRadius: 6,
                padding: '3px 10px',
                fontSize: 11,
                cursor: deleting ? 'not-allowed' : 'pointer',
                opacity: deleting ? 0.6 : 1,
              }}
            >
              Edit
            </button>
            <button
              onClick={handleDelete}
              disabled={deleting}
              style={{
                background: 'transparent',
                border: 'none',
                color: T.errorText,
                borderRadius: 6,
                padding: '3px 10px',
                fontSize: 11,
                cursor: deleting ? 'not-allowed' : 'pointer',
                opacity: deleting ? 0.6 : 1,
              }}
            >
              {deleting ? 'Deleting...' : 'Delete'}
            </button>
          </div>
        </div>
      </div>

      {/* Run history strip */}
      <RunHistoryStrip
        conn={conn}
        onViewLogs={(runId) => onViewLogs(conn.id, runId)}
      />
    </div>
  )
}

// ── Main List Component ──────────────────────────────────────────────────────

interface Props {
  connections: SourceConnection[]
  loading: boolean
  error: string | null
  onEdit: (id: string) => void
  onRefresh: () => void
}

export default function SourceConnectionsList({ connections, loading, error, onEdit, onRefresh }: Props) {
  const [logModal, setLogModal] = useState<{ connId: string; runId: string } | null>(null)

  return (
    <div style={{ flex: 1, overflowY: 'auto', padding: 24 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 20 }}>
        <span style={{ fontSize: 15, fontWeight: 700, color: T.textPrimary, flex: 1 }}>
          Source Connections
        </span>
      </div>

      {loading && (
        <div style={{ color: T.textMuted, fontSize: 13 }}>Loading connections...</div>
      )}

      {error && (
        <div style={{
          background: T.errorBg,
          border: `1px solid ${T.errorBorder}`,
          color: T.errorText,
          borderRadius: 8,
          padding: '10px 14px',
          fontSize: 13,
          marginBottom: 16,
        }}>
          {error}
        </div>
      )}

      {!loading && !error && connections.length === 0 && (
        <div style={{
          textAlign: 'center',
          padding: '48px 0',
          color: T.textVeryMuted,
          fontSize: 13,
        }}>
          No connections yet. Click "Add Connection" to get started.
        </div>
      )}

      {!loading && connections.map(conn => (
        <ConnectionCard
          key={conn.id}
          conn={conn}
          onEdit={onEdit}
          onRefresh={onRefresh}
          onViewLogs={(connId, runId) => setLogModal({ connId, runId })}
        />
      ))}

      {/* Log Modal */}
      {logModal && (
        <LogModal
          connId={logModal.connId}
          runId={logModal.runId}
          onClose={() => setLogModal(null)}
        />
      )}
    </div>
  )
}
