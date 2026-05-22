import { useState } from 'react'
import { T } from './theme'
import LoginScreen from './components/LoginScreen'
import ChatScreen from './components/ChatScreen'
import SourceConnectionsTab from './components/SourceConnectionsTab'
import UnifiedViewBuilderTab from './components/UnifiedViewBuilderTab'
import type { Session } from './types'

type Tab = 'chat' | 'connections' | 'unified'

export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [activeTab, setActiveTab] = useState<Tab>(
    window.location.search.includes('oauth_state_id') ? 'connections' : 'chat'
  )

  if (!session) {
    return <LoginScreen onLogin={setSession} />
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={styles.tabBar}>
        <button
          style={{
            ...styles.tab,
            ...(activeTab === 'chat' ? styles.tabActive : {}),
          }}
          onMouseEnter={e => {
            if (activeTab !== 'chat') {
              (e.currentTarget as HTMLButtonElement).style.background = T.border
              ;(e.currentTarget as HTMLButtonElement).style.color = T.textPrimary
            }
          }}
          onMouseLeave={e => {
            if (activeTab !== 'chat') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = T.textSecondary
            }
          }}
          onClick={() => setActiveTab('chat')}
        >
          💬 Chat
        </button>
        <button
          style={{
            ...styles.tab,
            ...(activeTab === 'connections' ? styles.tabActive : {}),
          }}
          onMouseEnter={e => {
            if (activeTab !== 'connections') {
              (e.currentTarget as HTMLButtonElement).style.background = T.border
              ;(e.currentTarget as HTMLButtonElement).style.color = T.textPrimary
            }
          }}
          onMouseLeave={e => {
            if (activeTab !== 'connections') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = T.textSecondary
            }
          }}
          onClick={() => setActiveTab('connections')}
        >
          🔌 Source Connections
        </button>
        <button
          style={{
            ...styles.tab,
            ...(activeTab === 'unified' ? styles.tabActive : {}),
          }}
          onMouseEnter={e => {
            if (activeTab !== 'unified') {
              (e.currentTarget as HTMLButtonElement).style.background = T.border
              ;(e.currentTarget as HTMLButtonElement).style.color = T.textPrimary
            }
          }}
          onMouseLeave={e => {
            if (activeTab !== 'unified') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = T.textSecondary
            }
          }}
          onClick={() => setActiveTab('unified')}
        >
          ✦ Unified View Builder
        </button>
      </div>
      {activeTab === 'chat'        && <ChatScreen session={session} onLogout={() => setSession(null)} />}
      {activeTab === 'connections' && <SourceConnectionsTab session={session} />}
      {activeTab === 'unified'     && <UnifiedViewBuilderTab />}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  tabBar: {
    display: 'flex',
    flexDirection: 'row',
    background: T.bgPage,
    borderBottom: `1px solid ${T.border}`,
    flexShrink: 0,
    height: '50px',
    alignItems: 'stretch',
  },
  tab: {
    height: '50px',
    padding: '0 20px',
    border: 'none',
    background: 'transparent',
    color: T.textSecondary,
    cursor: 'pointer',
    fontSize: 14,
    fontWeight: 500,
    transition: 'background 0.15s, color 0.15s',
  },
  tabActive: {
    background: T.accentTint,
    color: T.accentLight,
  },
}
