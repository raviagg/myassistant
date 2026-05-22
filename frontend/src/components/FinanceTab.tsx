import { useCallback, useEffect, useState } from 'react'
import type { Session, SourceConnection } from '../types'
import { listSourceConnections, listPlaidItems, type PlaidItem } from '../api'

interface Props {
  session: Session
}

export default function FinanceTab({ session }: Props) {
  const [connections, setConnections] = useState<SourceConnection[]>([])
  const [itemsByConn, setItemsByConn] = useState<Record<string, PlaidItem[]>>({})
  const [loading, setLoading]         = useState(true)
  const [error, setError]             = useState<string | null>(null)

  const loadData = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const conns = (await listSourceConnections(session.personId))
        .filter(c => c.sourceType === 'plaid_poll')
      setConnections(conns)
      const map: Record<string, PlaidItem[]> = {}
      await Promise.all(conns.map(async c => {
        map[c.id] = await listPlaidItems(c.id)
      }))
      setItemsByConn(map)
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [session.personId])

  useEffect(() => { loadData() }, [loadData])

  return (
    <div style={styles.wrapper}>
      <h2 style={styles.heading}>Connected Accounts</h2>
      <div style={{ color: 'var(--text-muted)', fontSize: 12, marginBottom: 16 }}>
        Manage connections in the Connections tab.
      </div>

      {error && <div style={styles.error}>{error}</div>}

      {loading ? (
        <div style={styles.muted}>Loading...</div>
      ) : connections.length === 0 ? (
        <div style={styles.muted}>No Plaid connections. Add one in the Connections tab.</div>
      ) : (
        <div style={styles.list}>
          {connections.map(conn => {
            const items = itemsByConn[conn.id] ?? []
            return (
              <div key={conn.id} style={styles.card}>
                <div style={styles.cardHeader}>
                  <span style={styles.connName}>{conn.connectionName}</span>
                  {conn.lastSyncedAt && (
                    <span style={styles.lastSynced}>
                      Synced {new Date(conn.lastSyncedAt).toLocaleDateString()}
                    </span>
                  )}
                </div>
                {items.length === 0 ? (
                  <div style={styles.muted}>No banks linked.</div>
                ) : (
                  items.map(item => (
                    <div key={item.id} style={styles.item}>
                      {item.institutionName}
                    </div>
                  ))
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:    { padding: '32px 24px', maxWidth: 640, margin: '0 auto' },
  heading:    { fontWeight: 600, fontSize: 18, marginBottom: 4, color: 'var(--text-primary)' },
  list:       { display: 'flex', flexDirection: 'column', gap: 12 },
  card:       { background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 8, padding: 16 },
  cardHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  connName:   { fontWeight: 600, fontSize: 15, color: 'var(--text-primary)' },
  lastSynced: { fontSize: 11, color: 'var(--text-muted)' },
  item:       { padding: '4px 0', color: 'var(--text-secondary)', fontSize: 13 },
  muted:      { color: 'var(--text-muted)', fontSize: 13, marginBottom: 12 },
  error:      { background: '#3d1a1a', color: '#ff6b6b', padding: '10px 14px', borderRadius: 6, marginBottom: 16, fontSize: 13 },
}
