import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import DebugPanel from './DebugPanel'
import type { Message, AttachedFile } from '../types'

function fileDownloadUrl(path: string) {
  return `/api/download?path=${encodeURIComponent(path)}`
}

function FileThumbnails({ files }: { files: AttachedFile[] }) {
  if (files.length === 0) return null
  return (
    <div style={thumbStyles.row}>
      {files.map(f => {
        const url = fileDownloadUrl(f.path)
        const isImage = f.mimeType.startsWith('image/')
        const isPdf   = f.mimeType === 'application/pdf'
        return (
          <a key={f.path} href={url} download={f.name} style={thumbStyles.card} title={f.name}>
            {isImage ? (
              <img src={url} alt={f.name} style={thumbStyles.img} />
            ) : (
              <div style={thumbStyles.icon}>{isPdf ? '📄' : '📎'}</div>
            )}
            <span style={thumbStyles.label}>{f.name}</span>
          </a>
        )
      })}
    </div>
  )
}

const thumbStyles: Record<string, React.CSSProperties> = {
  row:   { display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 8 },
  card:  { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, width: 320,
           background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 10,
           padding: '8px', textDecoration: 'none', cursor: 'pointer' },
  img:   { width: 304, height: 224, objectFit: 'cover', borderRadius: 6 },
  icon:  { fontSize: 64, lineHeight: '224px', height: 224 },
  label: { fontSize: 11, color: 'var(--text-secondary)', textAlign: 'center', wordBreak: 'break-all',
           maxWidth: 304, overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 1,
           WebkitBoxOrient: 'vertical' },
}

interface Props {
  message: Message
}

export default function MessageBubble({ message }: Props) {
  const isUser = message.role === 'user'

  const bubbleStyle = {
    ...styles.bubble,
    background: isUser ? 'var(--bubble-user)' : 'var(--bubble-asst)',
    color: isUser ? 'var(--bubble-user-text)' : 'var(--bubble-asst-text)',
    borderBottomRightRadius: isUser ? 3 : 12,
    borderBottomLeftRadius: isUser ? 12 : 3,
  }

  return (
    <div style={{ ...styles.wrapper, alignItems: isUser ? 'flex-end' : 'flex-start' }}>
      {isUser && message.filePaths && message.filePaths.length > 0 && (
        <div style={styles.fileTag}>
          📄 {message.filePaths.map(p => p.split('/').pop()).join(', ')}
        </div>
      )}
      <div style={bubbleStyle}>
        {isUser ? (
          <span style={{ whiteSpace: 'pre-wrap' }}>
            {message.text || (message.streaming ? '…' : '')}
            {message.streaming && <span style={styles.cursor}>|</span>}
          </span>
        ) : (
          <div className="md-body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {(message.text || (message.streaming ? '…' : '')) + (message.streaming ? ' ▍' : '')}
            </ReactMarkdown>
          </div>
        )}
      </div>
      {!isUser && message.attachedFiles && message.attachedFiles.length > 0 && (
        <FileThumbnails files={message.attachedFiles} />
      )}
      {!isUser && (
        <DebugPanel debugInfo={message.debugInfo} streaming={message.streaming} />
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper: { display: 'flex', flexDirection: 'column', maxWidth: '85%', gap: 4 },
  bubble:  { padding: '10px 14px', borderRadius: 12, fontSize: 15, lineHeight: 1.5 },
  fileTag: { fontSize: 11, background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 5, padding: '3px 8px', color: 'var(--text-secondary)' },
  cursor:  { opacity: 0.4, animation: 'blink 1s step-end infinite' },
}
