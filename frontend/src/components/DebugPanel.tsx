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
          {(() => {
            const rows: React.ReactNode[] = []
            let toolIdx = 0
            debugInfo.apiCalls.forEach((ac, i) => {
              rows.push(
                <div key={`ac-${i}`} style={styles.apiBlock}>
                  API {i + 1}: {ac.stats.durationMs}ms · in={ac.stats.inputTokens} hit={ac.stats.cacheReadTokens} out={ac.stats.outputTokens}
                </div>
              )
              const toolUseBlocks = ac.responseBlocks.filter(b => b.type === 'tool_use')
              toolUseBlocks.forEach((_, j) => {
                const tc = debugInfo.toolCalls[toolIdx++]
                if (!tc) return
                rows.push(
                  <div key={`tc-${i}-${j}`} style={styles.toolBlock}>
                    <div style={styles.toolName}>→ {tc.name}({JSON.stringify(tc.input).slice(0, 120)})</div>
                    <div style={styles.toolResult}>← {JSON.stringify(tc.result).slice(0, 200)}</div>
                  </div>
                )
              })
            })
            return rows
          })()}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:    { marginTop: 4, fontSize: 12 },
  toggle:     { display: 'flex', alignItems: 'center', gap: 5, background: 'none', color: 'var(--bubble-debug-text)', padding: '2px 0', fontSize: 12 },
  arrow:      { display: 'inline-block', transition: 'transform 0.15s', fontSize: 9 },
  body:       { marginTop: 6, background: 'var(--bubble-debug-bg)', border: '1px solid var(--bubble-debug-border)', borderRadius: 6, padding: 10, maxHeight: 200, overflowY: 'auto' },
  apiBlock:   { marginBottom: 2, padding: '3px 6px', color: 'var(--bubble-debug-text)', opacity: 0.7, fontFamily: 'monospace' },
  toolBlock:  { marginBottom: 4, padding: '4px 6px', background: 'var(--bg-surface2)', borderRadius: 4, borderLeft: '2px solid var(--accent-blue)' },
  toolName:   { color: 'var(--text-secondary)', fontFamily: 'monospace', wordBreak: 'break-all' },
  toolResult: { color: 'var(--text-muted)', fontFamily: 'monospace', marginTop: 2, wordBreak: 'break-all' },
}
