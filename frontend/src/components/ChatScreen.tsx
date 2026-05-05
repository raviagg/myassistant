import MessageList from './MessageList'
import InputBar from './InputBar'
import ProfileMenu from './ProfileMenu'
import { useChatStream } from '../hooks/useChatStream'
import type { Session } from '../types'

interface Props {
  session: Session
  onLogout: () => void
}

export default function ChatScreen({ session, onLogout }: Props) {
  const { messages, isStreaming, sendMessage } = useChatStream()

  function handleSend(text: string, files: File[]) {
    sendMessage(text, files, session.personId)
  }

  return (
    <div style={styles.wrapper}>
      <div style={styles.topbar}>
        <div style={styles.topbarLeft}>
          <div style={styles.logo}>🤖</div>
          <span style={styles.title}>Personal Assistant</span>
        </div>
        <ProfileMenu displayName={session.displayName} onLogout={onLogout} />
      </div>
      <MessageList messages={messages} />
      <InputBar onSend={handleSend} disabled={isStreaming} />
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:     { display: 'flex', flexDirection: 'column', height: '100vh', background: 'var(--bg-base)' },
  topbar:      { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 16px', background: 'var(--bg-surface2)', borderBottom: '1px solid var(--border)', flexShrink: 0 },
  topbarLeft:  { display: 'flex', alignItems: 'center', gap: 10 },
  logo:        { width: 28, height: 28, background: 'linear-gradient(135deg,#4a6fa5,#7c5cbf)', borderRadius: 7, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14 },
  title:       { fontWeight: 600, fontSize: 14 },
}
