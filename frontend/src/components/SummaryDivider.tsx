import { useState } from 'react'
import type { SummarizedTopic } from '../types'

interface Props {
  data: SummarizedTopic
}

export default function SummaryDivider({ data }: Props) {
  const [open, setOpen] = useState(false)

  return (
    <div style={styles.wrapper}>
      <div style={styles.row}>
        <div style={styles.line} />
        <button style={styles.toggle} onClick={() => setOpen(o => !o)}>
          <span style={{ ...styles.arrow, transform: open ? 'rotate(90deg)' : undefined }}>▶</span>
          <span>{data.messageCount} message{data.messageCount !== 1 ? 's' : ''} summarized · {data.topic}</span>
        </button>
        <div style={styles.line} />
      </div>
      {open && (
        <div style={styles.body}>
          {data.summaryText}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper: { margin: '12px 0', fontSize: 12 },
  row:     { display: 'flex', alignItems: 'center', gap: 8 },
  line:    { flex: 1, height: 1, background: 'var(--bubble-debug-border)' },
  toggle:  { display: 'flex', alignItems: 'center', gap: 5, background: 'none', color: 'var(--bubble-debug-text)', padding: '3px 8px', fontSize: 12, border: '1px solid var(--bubble-debug-border)', borderRadius: 12, whiteSpace: 'nowrap', cursor: 'pointer' },
  arrow:   { display: 'inline-block', transition: 'transform 0.15s', fontSize: 9 },
  body:    { marginTop: 6, background: 'var(--bubble-debug-bg)', border: '1px solid var(--bubble-debug-border)', borderRadius: 6, padding: 10, color: 'var(--bubble-debug-text)', lineHeight: 1.5 },
}
