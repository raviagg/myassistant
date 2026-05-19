import { useState, useEffect, useCallback } from 'react'
import { T } from '../theme'
import { listSourceConnections } from '../api'
import type { Session, SourceConnection } from '../types'
import SourceConnectionsList from './SourceConnectionsList'
import SourceConnectionForm from './SourceConnectionForm'

type SubTab = 'list' | 'add'

interface Props {
  session: Session
}

export default function SourceConnectionsTab({ session }: Props) {
  const [subTab, setSubTab] = useState<SubTab>('list')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [connections, setConnections] = useState<SourceConnection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadConnections = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const items = await listSourceConnections(session.personId)
      setConnections(items)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load connections')
    } finally {
      setLoading(false)
    }
  }, [session.personId])

  useEffect(() => {
    loadConnections()
  }, [loadConnections])

  const handleEdit = (id: string) => {
    setEditingId(id || null)
    setSubTab('add')
  }

  const handleSaved = () => {
    setSubTab('list')
    setEditingId(null)
    loadConnections()
  }

  const handleCancel = () => {
    setSubTab('list')
    setEditingId(null)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden', background: T.bgPage }}>
      {/* Sub-tab bar */}
      <div style={styles.subTabBar}>
        <button
          style={{
            ...styles.subTab,
            ...(subTab === 'list' ? styles.subTabActive : {}),
          }}
          onMouseEnter={e => {
            if (subTab !== 'list') {
              (e.currentTarget as HTMLButtonElement).style.background = '#334155'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#e2e8f0'
            }
          }}
          onMouseLeave={e => {
            if (subTab !== 'list') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#94a3b8'
            }
          }}
          onClick={() => { setSubTab('list'); setEditingId(null) }}
        >
          All Connections
        </button>
        <button
          style={{
            ...styles.subTab,
            ...(subTab === 'add' ? styles.subTabActive : {}),
          }}
          onMouseEnter={e => {
            if (subTab !== 'add') {
              (e.currentTarget as HTMLButtonElement).style.background = '#334155'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#e2e8f0'
            }
          }}
          onMouseLeave={e => {
            if (subTab !== 'add') {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              ;(e.currentTarget as HTMLButtonElement).style.color = '#94a3b8'
            }
          }}
          onClick={() => { setSubTab('add'); setEditingId(null) }}
        >
          Add Connection
        </button>
      </div>

      {/* Content */}
      <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        {subTab === 'list' && (
          <SourceConnectionsList
            connections={connections}
            loading={loading}
            error={error}
            onEdit={handleEdit}
            onRefresh={loadConnections}
            session={session}
          />
        )}
        {subTab === 'add' && (
          <SourceConnectionForm
            editingId={editingId}
            session={session}
            onSaved={handleSaved}
            onCancel={handleCancel}
          />
        )}
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  subTabBar: {
    display: 'flex',
    flexDirection: 'row',
    background: '#1e293b',
    borderBottom: '1px solid #334155',
    height: '40px',
    alignItems: 'stretch',
    flexShrink: 0,
  },
  subTab: {
    height: '40px',
    padding: '0 16px',
    border: 'none',
    background: 'transparent',
    color: '#94a3b8',
    cursor: 'pointer',
    fontSize: 12,
    fontWeight: 500,
    transition: 'background 0.15s, color 0.15s',
  },
  subTabActive: {
    background: 'rgba(99,102,241,0.15)',
    color: '#a5b4fc',
  },
}
