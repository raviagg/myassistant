import { useState } from 'react'
import type { DebugInfo } from '../types'

interface Props {
  debugInfo?: DebugInfo
  streaming?: boolean
}

export default function DebugPanel({ debugInfo, streaming }: Props) {
  const [open, setOpen] = useState(false)

  const totalCalls = debugInfo?.toolCalls.length ?? 0
  const totalMs    = debugInfo?.apiCalls.reduce((s, c) => s + c.stats.durationMs, 0) ?? 0
  const totalIn    = debugInfo?.apiCalls.reduce((s, c) => s + c.stats.inputTokens, 0) ?? 0
  const totalHit   = debugInfo?.apiCalls.reduce((s, c) => s + c.stats.cacheReadTokens, 0) ?? 0
  const totalOut   = debugInfo?.apiCalls.reduce((s, c) => s + c.stats.outputTokens, 0) ?? 0

  const summary = streaming
    ? `${totalCalls} tool call${totalCalls !== 1 ? 's' : ''} · streaming…`
    : `${totalCalls} tool call${totalCalls !== 1 ? 's' : ''} · ${(totalMs / 1000).toFixed(1)}s · in=${totalIn.toLocaleString()} hit=${totalHit.toLocaleString()} out=${totalOut.toLocaleString()}`

  return (
    <div style={styles.wrapper}>
      <button style={styles.toggle} onClick={() => setOpen(o => !o)}>
        <span style={{ ...styles.arrow, transform: open ? 'rotate(90deg)' : undefined }}>▶</span>
        <span>{summary}</span>
      </button>
      {open && debugInfo && (
        <div style={styles.body}>
          {debugInfo.toolCalls.map((tc, i) => (
            <div key={i} style={styles.toolBlock}>
              <div style={styles.toolName}>→ {tc.name}({JSON.stringify(tc.input).slice(0, 120)})</div>
              <div style={styles.toolResult}>← {JSON.stringify(tc.result).slice(0, 200)}</div>
            </div>
          ))}
          {debugInfo.apiCalls.map((ac, i) => (
            <div key={`ac-${i}`} style={{ ...styles.toolBlock, marginTop: 4 }}>
              <div style={styles.toolName}>
                API call {i + 1}: {ac.stats.durationMs}ms in={ac.stats.inputTokens} hit={ac.stats.cacheReadTokens} out={ac.stats.outputTokens}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:    { marginTop: 4, fontSize: 11 },
  toggle:     { display: 'flex', alignItems: 'center', gap: 5, background: 'none', color: 'var(--text-muted)', padding: '2px 0', fontSize: 11 },
  arrow:      { display: 'inline-block', transition: 'transform 0.15s', fontSize: 9 },
  body:       { marginTop: 6, background: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: 6, padding: 10, maxHeight: 200, overflowY: 'auto' },
  toolBlock:  { marginBottom: 4, padding: '4px 6px', background: 'var(--bg-surface2)', borderRadius: 4, borderLeft: '2px solid var(--accent-blue)' },
  toolName:   { color: 'var(--text-secondary)', fontFamily: 'monospace', wordBreak: 'break-all' },
  toolResult: { color: 'var(--text-muted)', fontFamily: 'monospace', marginTop: 2, wordBreak: 'break-all' },
}
