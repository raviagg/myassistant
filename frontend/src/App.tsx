import { useState } from 'react'
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
              (e.currentTarget as HTMLButtonElement).style.background = '#334155'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#e2e8f0'
            }
          }}
          onMouseLeave={e => {
            if (activeTab !== 'chat') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#94a3b8'
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
              (e.currentTarget as HTMLButtonElement).style.background = '#334155'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#e2e8f0'
            }
          }}
          onMouseLeave={e => {
            if (activeTab !== 'connections') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#94a3b8'
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
              (e.currentTarget as HTMLButtonElement).style.background = '#334155'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#e2e8f0'
            }
          }}
          onMouseLeave={e => {
            if (activeTab !== 'unified') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#94a3b8'
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
    background: '#0f172a',
    borderBottom: '1px solid #334155',
    flexShrink: 0,
    height: '50px',
    alignItems: 'stretch',
  },
  tab: {
    height: '50px',
    padding: '0 20px',
    border: 'none',
    background: 'transparent',
    color: '#94a3b8',
    cursor: 'pointer',
    fontSize: 14,
    fontWeight: 500,
    transition: 'background 0.15s, color 0.15s',
  },
  tabActive: {
    background: 'rgba(99,102,241,0.15)',
    color: '#a5b4fc',
  },
}
