import { useState } from 'react'
import type { ContextInfo } from '../types'

interface Props {
  contextInfo: ContextInfo
}

export default function ContextBar({ contextInfo }: Props) {
  const [open, setOpen] = useState(false)

  const { rawTurnCount, currentTopic, allSegments = [], summarizedTopics = [] } = contextInfo

  const summary = [
    `💬 ${rawTurnCount} message${rawTurnCount !== 1 ? 's' : ''}`,
    currentTopic && `Topic: ${currentTopic}`,
    summarizedTopics.length > 0 && `${summarizedTopics.length} topic${summarizedTopics.length !== 1 ? 's' : ''} summarized`,
  ].filter(Boolean).join('  ·  ')

  return (
    <div style={styles.wrapper}>
      <button style={styles.header} onClick={() => setOpen(o => !o)}>
        <span style={{ ...styles.arrow, transform: open ? 'rotate(90deg)' : undefined }}>▶</span>
        <span style={styles.summary}>{summary}</span>
      </button>
      {open && (
        <div style={styles.panel}>
          {allSegments.length === 0 && (
            <div style={styles.empty}>No topic segments yet.</div>
          )}
          {allSegments.map((seg, i) => (
            <div key={i} style={styles.segRow}>
              <div style={styles.segHeader}>
                <span style={seg.summarized ? styles.badgeSummarized : seg.complete ? styles.badgeComplete : styles.badgeActive}>
                  {seg.summarized ? '📦 summarized' : seg.complete ? '✓ complete' : '💬 active'}
                </span>
                <span style={styles.segTopic}>{seg.topic}</span>
                <span style={styles.segCount}>{seg.messageCount} msg{seg.messageCount !== 1 ? 's' : ''}</span>
              </div>
              {seg.summarized && seg.summaryText && (
                <div style={styles.segSummary}>{seg.summaryText}</div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:          { background: 'var(--bg-surface2)', borderBottom: '1px solid var(--border)', flexShrink: 0 },
  header:           { display: 'flex', alignItems: 'center', gap: 6, width: '100%', padding: '5px 16px', background: 'none', color: 'var(--bubble-debug-text)', fontSize: 11, cursor: 'pointer', textAlign: 'left' },
  arrow:            { display: 'inline-block', transition: 'transform 0.15s', fontSize: 8, opacity: 0.6 },
  summary:          { flex: 1 },
  panel:            { borderTop: '1px solid var(--bubble-debug-border)', padding: '8px 16px', display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 240, overflowY: 'auto' },
  empty:            { color: 'var(--text-muted)', fontSize: 11, fontStyle: 'italic' },
  segRow:           { padding: '5px 8px', background: 'var(--bubble-debug-bg)', borderRadius: 5, border: '1px solid var(--bubble-debug-border)' },
  segHeader:        { display: 'flex', alignItems: 'center', gap: 8, fontSize: 11 },
  segTopic:         { flex: 1, color: 'var(--text-primary)', fontWeight: 500 },
  segCount:         { color: 'var(--text-muted)', fontSize: 10, whiteSpace: 'nowrap' },
  badgeActive:      { fontSize: 10, color: 'var(--accent-blue)', whiteSpace: 'nowrap' },
  badgeComplete:    { fontSize: 10, color: 'var(--text-muted)', whiteSpace: 'nowrap' },
  badgeSummarized:  { fontSize: 10, color: 'var(--text-secondary)', whiteSpace: 'nowrap' },
  segSummary:       { marginTop: 4, fontSize: 10, color: 'var(--bubble-debug-text)', lineHeight: 1.4, paddingLeft: 4, borderLeft: '2px solid var(--bubble-debug-border)' },
}
