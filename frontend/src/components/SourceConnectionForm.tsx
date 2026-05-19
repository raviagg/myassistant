import { useState, useEffect } from 'react'
import { usePlaidLink } from 'react-plaid-link'
import { T } from '../theme'
import { fetchLinkToken, exchangeToken, createSourceConnection, updateSourceConnection, getSourceConnection } from '../api'
import type { Session, SourceConnection } from '../types'

// ── Helpers ──────────────────────────────────────────────────────────────────

function parseCronHint(cron: string): string {
  const parts = cron.trim().split(/\s+/)
  if (parts.length !== 5) return cron
  const [min, hour] = parts
  if (min.startsWith('*/') && hour === '*') {
    const n = min.slice(2)
    return `Every ${n} minutes`
  }
  if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
    const h = parseInt(hour, 10)
    const period = h >= 12 ? 'PM' : 'AM'
    const h12 = h % 12 === 0 ? 12 : h % 12
    return `Daily at ${h12}:${min.padStart(2, '0')} ${period}`
  }
  return cron
}

// ── Section label style ───────────────────────────────────────────────────────

const sectionLabel: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.07em',
  color: T.textMuted,
  marginBottom: 10,
  display: 'block',
}

// ── Plaid Link inner component ────────────────────────────────────────────────
// Must be a separate component so usePlaidLink is always called with a defined token.

interface PlaidLinkButtonProps {
  token: string
  personId: string
  onSuccess: () => void
  onExit: () => void
}

function PlaidLinkButton({ token, personId, onSuccess, onExit }: PlaidLinkButtonProps) {
  const [exchanging, setExchanging] = useState(false)
  const [exchangeError, setExchangeError] = useState<string | null>(null)

  const { open, ready } = usePlaidLink({
    token,
    onSuccess: async (publicToken) => {
      setExchanging(true)
      setExchangeError(null)
      try {
        await exchangeToken(personId, publicToken)
        onSuccess()
      } catch (e) {
        setExchangeError(e instanceof Error ? e.message : 'Exchange failed')
        setExchanging(false)
      }
    },
    onExit: () => {
      onExit()
    },
  })

  return (
    <div>
      <button
        onClick={() => open()}
        disabled={!ready || exchanging}
        style={{
          background: T.accent,
          border: 'none',
          color: '#fff',
          borderRadius: 8,
          padding: '10px 20px',
          fontSize: 14,
          fontWeight: 600,
          cursor: !ready || exchanging ? 'not-allowed' : 'pointer',
          opacity: !ready || exchanging ? 0.6 : 1,
        }}
      >
        {exchanging ? 'Connecting...' : ready ? 'Connect via Plaid' : 'Preparing...'}
      </button>
      {exchangeError && (
        <div style={{ color: T.errorText, fontSize: 12, marginTop: 8 }}>{exchangeError}</div>
      )}
    </div>
  )
}

// ── Connector type chip ───────────────────────────────────────────────────────

interface ConnectorChipProps {
  icon: string
  label: string
  value: string
  selected: boolean
  disabled: boolean
  onSelect: (v: string) => void
}

function ConnectorChip({ icon, label, value, selected, disabled, onSelect }: ConnectorChipProps) {
  return (
    <button
      onClick={() => !disabled && onSelect(value)}
      disabled={disabled}
      style={{
        border: `1px solid ${selected ? 'rgba(99,102,241,0.5)' : T.border}`,
        borderRadius: 8,
        padding: '10px 14px',
        background: selected ? T.accentTint : 'transparent',
        color: disabled ? T.textVeryMuted : T.textPrimary,
        cursor: disabled ? 'not-allowed' : 'pointer',
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        fontSize: 13,
        fontWeight: 500,
        textAlign: 'left',
      }}
    >
      <span>{icon}</span>
      <span>{label}</span>
      {disabled && value !== 'plaid_poll' && (
        <span style={{ color: T.textVeryMuted, fontSize: 11 }}>(coming soon)</span>
      )}
    </button>
  )
}

// ── Main Form ─────────────────────────────────────────────────────────────────

interface Props {
  editingId: string | null
  session: Session
  onSaved: () => void
  onCancel: () => void
}

export default function SourceConnectionForm({ editingId, session, onSaved, onCancel }: Props) {
  const isEditing = editingId !== null

  // Form state
  const [sourceType, setSourceType] = useState<string>('plaid_poll')
  const [connectionName, setConnectionName] = useState('')
  const [syncScheduled, setSyncScheduled] = useState(false)
  const [syncAdhoc, setSyncAdhoc] = useState(true)
  const [syncSchedule, setSyncSchedule] = useState('0 2 * * *')
  const [existingConn, setExistingConn] = useState<SourceConnection | null>(null)

  // Plaid Link token state
  const [linkToken, setLinkToken] = useState<string | null>(null)
  const [fetchingToken, setFetchingToken] = useState(false)
  const [tokenError, setTokenError] = useState<string | null>(null)
  const [plaidInitiated, setPlaidInitiated] = useState(false)

  // General form state
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  // Load existing connection for edit
  useEffect(() => {
    if (!isEditing || !editingId) return

    const load = async () => {
      try {
        const conn = await getSourceConnection(editingId)
        setExistingConn(conn)
        setSourceType(conn.sourceType)
        setConnectionName(conn.connectionName)
        setSyncScheduled(conn.syncScheduled)
        setSyncAdhoc(conn.syncAdhoc)
        setSyncSchedule(conn.syncSchedule ?? '0 2 * * *')
      } catch (e) {
        setLoadError(e instanceof Error ? e.message : 'Failed to load connection')
      }
    }
    load()
  }, [editingId, isEditing])

  // For new Plaid connections: fetch link token when user clicks "Connect via Plaid"
  const handleInitiatePlaid = async () => {
    if (!connectionName.trim()) {
      setTokenError('Please enter a connection name first.')
      return
    }
    setFetchingToken(true)
    setTokenError(null)
    try {
      const token = await fetchLinkToken(session.personId)
      setLinkToken(token)
      setPlaidInitiated(true)
    } catch (e) {
      setTokenError(e instanceof Error ? e.message : 'Failed to get link token')
    } finally {
      setFetchingToken(false)
    }
  }

  const handlePlaidSuccess = () => {
    // Server has already created the source_connections row in exchange handler.
    // Call onSaved to refresh the list.
    onSaved()
  }

  const handlePlaidExit = () => {
    setPlaidInitiated(false)
    setLinkToken(null)
  }

  // Save for non-Plaid add or edit of any type
  const handleSave = async () => {
    if (!connectionName.trim()) {
      setSaveError('Connection name is required.')
      return
    }
    setSaving(true)
    setSaveError(null)
    try {
      if (isEditing && editingId && existingConn) {
        await updateSourceConnection(editingId, {
          sourceType: existingConn.sourceType,
          connectionName: connectionName.trim(),
          personId: existingConn.personId,
          householdId: existingConn.householdId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : null,
          config: existingConn.config,
        })
      } else {
        // Non-Plaid add (shouldn't normally reach here for plaid)
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
        })
      }
      onSaved()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  const showSyncOptions = sourceType !== 'chatbot'
  const isNewPlaid = !isEditing && sourceType === 'plaid_poll'

  return (
    <div style={{ flex: 1, overflowY: 'auto', padding: 24, maxWidth: 560 }}>
      {loadError && (
        <div style={{
          background: T.errorBg,
          border: `1px solid ${T.errorBorder}`,
          color: T.errorText,
          borderRadius: 8,
          padding: '10px 14px',
          fontSize: 13,
          marginBottom: 16,
        }}>
          {loadError}
        </div>
      )}

      {/* Section 1: Connector Type */}
      <div style={{ marginBottom: 24 }}>
        <span style={sectionLabel}>Connector Type</span>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <ConnectorChip
            icon="🏦"
            label="Plaid"
            value="plaid_poll"
            selected={sourceType === 'plaid_poll'}
            disabled={isEditing}
            onSelect={setSourceType}
          />
          <ConnectorChip
            icon="📧"
            label="Gmail"
            value="gmail_poll"
            selected={sourceType === 'gmail_poll'}
            disabled={true}
            onSelect={setSourceType}
          />
          <ConnectorChip
            icon="📰"
            label="News"
            value="news_poll"
            selected={sourceType === 'news_poll'}
            disabled={true}
            onSelect={setSourceType}
          />
          <ConnectorChip
            icon="💬"
            label="Chatbot"
            value="chatbot"
            selected={sourceType === 'chatbot'}
            disabled={true}
            onSelect={setSourceType}
          />
        </div>
      </div>

      {/* Section 2: Connection Name */}
      <div style={{ marginBottom: 24 }}>
        <span style={sectionLabel}>Connection Name</span>
        <input
          type="text"
          value={connectionName}
          onChange={e => setConnectionName(e.target.value)}
          placeholder="e.g. Ravi's Chase"
          style={{
            width: '100%',
            background: T.bgInput,
            border: `1px solid ${T.border}`,
            borderRadius: 8,
            padding: '10px 12px',
            color: T.textPrimary,
            fontSize: 14,
            outline: 'none',
            boxSizing: 'border-box',
          }}
        />
      </div>

      {/* Section 3: Plaid-specific */}
      {sourceType === 'plaid_poll' && (
        <div style={{ marginBottom: 24 }}>
          <span style={sectionLabel}>Bank Connection</span>
          {isEditing ? (
            <div style={{
              background: T.bgRunStrip,
              border: `1px solid ${T.borderRun}`,
              borderRadius: 8,
              padding: '10px 14px',
              color: T.textSecondary,
              fontSize: 13,
            }}>
              Secrets: ••••••• (stored encrypted)
            </div>
          ) : (
            <div>
              {!plaidInitiated ? (
                <div>
                  <button
                    onClick={handleInitiatePlaid}
                    disabled={fetchingToken}
                    style={{
                      background: T.accent,
                      border: 'none',
                      color: '#fff',
                      borderRadius: 8,
                      padding: '10px 20px',
                      fontSize: 14,
                      fontWeight: 600,
                      cursor: fetchingToken ? 'not-allowed' : 'pointer',
                      opacity: fetchingToken ? 0.6 : 1,
                    }}
                  >
                    {fetchingToken ? 'Preparing...' : 'Connect via Plaid'}
                  </button>
                  {tokenError && (
                    <div style={{ color: T.errorText, fontSize: 12, marginTop: 8 }}>{tokenError}</div>
                  )}
                  <div style={{ color: T.textMuted, fontSize: 12, marginTop: 8 }}>
                    You'll be redirected to Plaid to securely link your bank account.
                  </div>
                </div>
              ) : linkToken ? (
                <PlaidLinkButton
                  token={linkToken}
                  personId={session.personId}
                  onSuccess={handlePlaidSuccess}
                  onExit={handlePlaidExit}
                />
              ) : null}
            </div>
          )}
        </div>
      )}

      {/* Section 4: Sync Options */}
      {showSyncOptions && (
        <div style={{ marginBottom: 24 }}>
          <span style={sectionLabel}>Sync Options</span>

          <label style={{
            display: 'flex',
            alignItems: 'flex-start',
            gap: 10,
            marginBottom: 14,
            cursor: 'pointer',
          }}>
            <input
              type="checkbox"
              checked={syncScheduled}
              onChange={e => setSyncScheduled(e.target.checked)}
              style={{ marginTop: 2, accentColor: T.accent }}
            />
            <div>
              <div style={{ color: T.textPrimary, fontSize: 13, fontWeight: 500 }}>
                Sync on schedule
              </div>
              <div style={{ color: T.textMuted, fontSize: 12 }}>
                Automatically sync at a scheduled interval
              </div>
            </div>
          </label>

          {syncScheduled && (
            <div style={{ marginBottom: 14, paddingLeft: 26 }}>
              <input
                type="text"
                value={syncSchedule}
                onChange={e => setSyncSchedule(e.target.value)}
                placeholder="0 2 * * *"
                style={{
                  background: T.bgInput,
                  border: `1px solid ${T.border}`,
                  borderRadius: 6,
                  padding: '8px 10px',
                  color: T.textPrimary,
                  fontSize: 13,
                  fontFamily: "'SF Mono', 'Fira Code', monospace",
                  outline: 'none',
                  width: 180,
                }}
              />
              {syncSchedule.trim() && (
                <div style={{ color: T.textMuted, fontSize: 11, marginTop: 4 }}>
                  {parseCronHint(syncSchedule)}
                </div>
              )}
            </div>
          )}

          <label style={{
            display: 'flex',
            alignItems: 'flex-start',
            gap: 10,
            cursor: 'pointer',
          }}>
            <input
              type="checkbox"
              checked={syncAdhoc}
              onChange={e => setSyncAdhoc(e.target.checked)}
              style={{ marginTop: 2, accentColor: T.accent }}
            />
            <div>
              <div style={{ color: T.textPrimary, fontSize: 13, fontWeight: 500 }}>
                Allow adhoc sync
              </div>
              <div style={{ color: T.textMuted, fontSize: 12 }}>
                Enable manual "Sync Now" button on the connection card
              </div>
            </div>
          </label>
        </div>
      )}

      {saveError && (
        <div style={{
          background: T.errorBg,
          border: `1px solid ${T.errorBorder}`,
          color: T.errorText,
          borderRadius: 8,
          padding: '10px 14px',
          fontSize: 13,
          marginBottom: 16,
        }}>
          {saveError}
        </div>
      )}

      {/* Section 5: Action buttons */}
      <div style={{ display: 'flex', gap: 10 }}>
        {/* For new Plaid: no Save button (onSaved is called from PlaidLinkButton) */}
        {/* For edit or non-Plaid add: show Save button */}
        {(isEditing || !isNewPlaid) && (
          <button
            onClick={handleSave}
            disabled={saving}
            style={{
              background: T.accent,
              border: 'none',
              color: '#fff',
              borderRadius: 8,
              padding: '10px 20px',
              fontSize: 14,
              fontWeight: 600,
              cursor: saving ? 'not-allowed' : 'pointer',
              opacity: saving ? 0.6 : 1,
            }}
          >
            {saving ? 'Saving...' : 'Save Connection'}
          </button>
        )}
        <button
          onClick={onCancel}
          style={{
            background: 'transparent',
            border: `1px solid ${T.border}`,
            color: T.textSecondary,
            borderRadius: 8,
            padding: '10px 20px',
            fontSize: 14,
            fontWeight: 500,
            cursor: 'pointer',
          }}
        >
          Cancel
        </button>
      </div>
    </div>
  )
}
