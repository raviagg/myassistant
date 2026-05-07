import { useState, useRef, KeyboardEvent, ChangeEvent } from 'react'

interface Props {
  onSend: (text: string, files: File[]) => void
  disabled: boolean
}

export default function InputBar({ onSend, disabled }: Props) {
  const [text, setText]   = useState('')
  const [files, setFiles] = useState<File[]>([])
  const fileRef           = useRef<HTMLInputElement>(null)

  function handleSend() {
    if (!text.trim() && files.length === 0) return
    onSend(text.trim(), files)
    setText('')
    setFiles([])
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  function handleFiles(e: ChangeEvent<HTMLInputElement>) {
    if (e.target.files) {
      const picked = Array.from(e.target.files)
      setFiles(prev => [...prev, ...picked])
    }
    e.target.value = ''
  }

  function removeFile(i: number) {
    setFiles(prev => prev.filter((_, idx) => idx !== i))
  }

  return (
    <div style={styles.wrapper}>
      {files.length > 0 && (
        <div style={styles.chips}>
          {files.map((f, i) => (
            <span key={i} style={styles.chip}>
              📎 {f.name}
              <button style={styles.chipRemove} onClick={() => removeFile(i)}>✕</button>
            </span>
          ))}
        </div>
      )}
      <div style={styles.box}>
        <button style={styles.fileBtn} onClick={() => fileRef.current?.click()} disabled={disabled} title="Attach file">📎</button>
        <input ref={fileRef} type="file" style={{ display: 'none' }} multiple onChange={handleFiles} />
        <textarea
          style={styles.textarea}
          placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
          value={text}
          onChange={e => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          rows={1}
        />
        <button
          style={{ ...styles.sendBtn, opacity: (disabled || (!text.trim() && !files.length)) ? 0.4 : 1 }}
          onClick={handleSend}
          disabled={disabled || (!text.trim() && files.length === 0)}
        >↑</button>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:    { padding: '10px 14px', borderTop: '1px solid var(--border)', flexShrink: 0 },
  chips:      { display: 'flex', gap: 6, marginBottom: 6, flexWrap: 'wrap' },
  chip:       { display: 'flex', alignItems: 'center', gap: 5, background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 5, padding: '3px 8px', fontSize: 11, color: 'var(--text-secondary)' },
  chipRemove: { background: 'none', color: 'var(--text-muted)', fontSize: 11, padding: 0 },
  box:        { display: 'flex', alignItems: 'flex-end', gap: 8, background: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: 10, padding: '8px 10px' },
  fileBtn:    { width: 30, height: 30, background: 'none', color: 'var(--text-muted)', fontSize: 17, borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'center' },
  textarea:   { flex: 1, background: 'none', border: 'none', color: 'var(--text-primary)', fontSize: 15, resize: 'none', fontFamily: 'inherit', minHeight: 20, maxHeight: 90, lineHeight: 1.5 },
  sendBtn:    { width: 30, height: 30, background: 'linear-gradient(135deg,#4a6fa5,#7c5cbf)', borderRadius: 7, color: '#fff', fontSize: 15, display: 'flex', alignItems: 'center', justifyContent: 'center' },
}
