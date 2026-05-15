import { useState } from 'react'
import LoginScreen from './components/LoginScreen'
import ChatScreen from './components/ChatScreen'
import FinanceTab from './components/FinanceTab'
import type { Session } from './types'

type Tab = 'chat' | 'finance'

export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [activeTab, setActiveTab] = useState<Tab>('chat')

  if (!session) {
    return <LoginScreen onLogin={setSession} />
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={styles.tabBar}>
        <button
          style={{ ...styles.tab, ...(activeTab === 'chat' ? styles.tabActive : {}) }}
          onClick={() => setActiveTab('chat')}
        >
          Chat
        </button>
        <button
          style={{ ...styles.tab, ...(activeTab === 'finance' ? styles.tabActive : {}) }}
          onClick={() => setActiveTab('finance')}
        >
          Finance
        </button>
      </div>
      {activeTab === 'chat'    && <ChatScreen session={session} onLogout={() => setSession(null)} />}
      {activeTab === 'finance' && <FinanceTab session={session} />}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  tabBar: {
    display: 'flex',
    background: 'var(--bg-surface2)',
    borderBottom: '1px solid var(--border)',
    flexShrink: 0,
  },
  tab: {
    padding: '10px 24px',
    border: 'none',
    background: 'transparent',
    color: 'var(--text-muted)',
    cursor: 'pointer',
    fontSize: 14,
    fontWeight: 500,
  },
  tabActive: {
    color: 'var(--text-primary)',
    borderBottom: '2px solid var(--accent)',
  },
}
