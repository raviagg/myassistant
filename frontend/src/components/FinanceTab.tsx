import { useCallback, useEffect, useState } from 'react'
import { usePlaidLink } from 'react-plaid-link'
import type { Session } from '../types'
import {
  disconnectPlaidAccount,
  exchangeToken,
  fetchLinkToken,
  listBankAccounts,
  listPlaidConnections,
  type BankAccount,
  type PlaidConnection,
} from '../api'

interface Props {
  session: Session
}

export default function FinanceTab({ session }: Props) {
  const [connections, setConnections]   = useState<PlaidConnection[]>([])
  const [accounts, setAccounts]         = useState<BankAccount[]>([])
  const [linkToken, setLinkToken]       = useState<string | null>(null)
  const [loading, setLoading]           = useState(true)
  const [error, setError]               = useState<string | null>(null)
  const [connecting, setConnecting]     = useState(false)
  const [disconnecting, setDisconnecting] = useState<string | null>(null)

  const loadData = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [conns, accts] = await Promise.all([
        listPlaidConnections(session.personId),
        listBankAccounts(session.personId),
      ])
      setConnections(conns)
      setAccounts(accts)
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [session.personId])

  useEffect(() => { loadData() }, [loadData])

  const handleConnectClick = async () => {
    setConnecting(true)
    setError(null)
    try {
      const token = await fetchLinkToken(session.personId)
      setLinkToken(token)
    } catch (e) {
      setError(String(e))
      setConnecting(false)
    }
  }

  const onPlaidSuccess = useCallback(async (publicToken: string) => {
    setError(null)
    try {
      await exchangeToken(session.personId, publicToken)
      setLinkToken(null)
      setConnecting(false)
      await loadData()
    } catch (e) {
      setError(String(e))
      setConnecting(false)
    }
  }, [session.personId, loadData])

  const onPlaidExit = useCallback(() => {
    setLinkToken(null)
    setConnecting(false)
  }, [])

  const handleDisconnect = async (conn: PlaidConnection) => {
    setDisconnecting(conn.entityInstanceId)
    setError(null)
    try {
      await disconnectPlaidAccount(conn.entityInstanceId, conn.schemaId, session.personId)
      await loadData()
    } catch (e) {
      setError(String(e))
    } finally {
      setDisconnecting(null)
    }
  }

  const accountsByItemId = accounts.reduce<Record<string, BankAccount[]>>((acc, a) => {
    const key = a.fields.item_id
    if (!acc[key]) acc[key] = []
    acc[key].push(a)
    return acc
  }, {})

  return (
    <div style={styles.wrapper}>
      <h2 style={styles.heading}>Connected Accounts</h2>

      {error && <div style={styles.error}>{error}</div>}

      {loading ? (
        <div style={styles.muted}>Loading...</div>
      ) : connections.length === 0 ? (
        <div style={styles.muted}>No accounts connected yet.</div>
      ) : (
        <div style={styles.list}>
          {connections.map(conn => {
            const connAccounts = accountsByItemId[conn.fields.item_id] ?? []
            return (
              <div key={conn.entityInstanceId} style={styles.card}>
                <div style={styles.cardHeader}>
                  <span style={styles.institution}>{conn.fields.institution_name}</span>
                  <button
                    style={styles.disconnectBtn}
                    disabled={disconnecting === conn.entityInstanceId}
                    onClick={() => handleDisconnect(conn)}
                  >
                    {disconnecting === conn.entityInstanceId ? 'Disconnecting...' : 'Disconnect'}
                  </button>
                </div>
                {connAccounts.map(acct => (
                  <div key={acct.entityInstanceId} style={styles.account}>
                    <span style={styles.accountName}>
                      {acct.fields.name}
                      {acct.fields.mask ? ` ····${acct.fields.mask}` : ''}
                    </span>
                    {acct.fields.current_balance != null && (
                      <span style={styles.balance}>
                        {acct.fields.iso_currency_code ?? ''} {acct.fields.current_balance.toFixed(2)}
                      </span>
                    )}
                  </div>
                ))}
                {conn.fields.last_synced_at && (
                  <div style={styles.lastSynced}>
                    Last synced: {new Date(conn.fields.last_synced_at).toLocaleString()}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}

      <PlaidLinkButton
        linkToken={linkToken}
        onSuccess={onPlaidSuccess}
        onExit={onPlaidExit}
        onConnectClick={handleConnectClick}
        connecting={connecting}
      />
    </div>
  )
}

function PlaidLinkButton({
  linkToken,
  onSuccess,
  onExit,
  onConnectClick,
  connecting,
}: {
  linkToken: string | null
  onSuccess: (token: string) => void
  onExit: () => void
  onConnectClick: () => void
  connecting: boolean
}) {
  const { open, ready } = usePlaidLink({
    token: linkToken ?? '',
    onSuccess: (public_token) => onSuccess(public_token),
    onExit: () => onExit(),
  })

  useEffect(() => {
    if (linkToken && ready) open()
  }, [linkToken, ready, open])

  return (
    <button
      style={styles.connectBtn}
      onClick={onConnectClick}
      disabled={connecting}
    >
      {connecting ? 'Opening Plaid...' : '+ Connect account'}
    </button>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:      { padding: '32px 24px', maxWidth: 640, margin: '0 auto' },
  heading:      { fontWeight: 600, fontSize: 18, marginBottom: 20, color: 'var(--text-primary)' },
  list:         { display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 24 },
  card:         { background: 'var(--bg-surface2)', border: '1px solid var(--border)', borderRadius: 8, padding: 16 },
  cardHeader:   { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 },
  institution:  { fontWeight: 600, fontSize: 15, color: 'var(--text-primary)' },
  disconnectBtn:{ padding: '4px 12px', fontSize: 12, borderRadius: 4, border: '1px solid var(--border)', background: 'transparent', color: 'var(--text-muted)', cursor: 'pointer' },
  account:      { display: 'flex', justifyContent: 'space-between', padding: '4px 0', color: 'var(--text-secondary)', fontSize: 13 },
  accountName:  { color: 'var(--text-secondary)' },
  balance:      { color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' },
  lastSynced:   { fontSize: 11, color: 'var(--text-muted)', marginTop: 8 },
  connectBtn:   { marginTop: 8, padding: '10px 20px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 14, fontWeight: 500 },
  muted:        { color: 'var(--text-muted)', marginBottom: 24 },
  error:        { background: '#3d1a1a', color: '#ff6b6b', padding: '10px 14px', borderRadius: 6, marginBottom: 16, fontSize: 13 },
}
