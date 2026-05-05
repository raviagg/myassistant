import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import DebugPanel from './DebugPanel'
import type { Message } from '../types'

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
