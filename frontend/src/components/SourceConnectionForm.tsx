import { useState, useEffect } from 'react'
import { usePlaidLink } from 'react-plaid-link'
import { T } from '../theme'
import {
  fetchLinkTokenForConnection,
  exchangeTokenForConnection,
  listPlaidItems,
  disconnectPlaidItem,
  createSourceConnection,
  updateSourceConnection,
  getSourceConnection,
  type PlaidItem,
} from '../api'
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
  onSuccess: (publicToken: string) => void
  onExit: () => void
}

function PlaidLinkButton({ token, onSuccess, onExit }: PlaidLinkButtonProps) {
  const { open, ready } = usePlaidLink({
    token,
    onSuccess: (publicToken) => {
      onSuccess(publicToken)
    },
    onExit: () => {
      onExit()
    },
  })

  return (
    <div>
      <button
        onClick={() => open()}
        disabled={!ready}
        style={{
          background: T.accent,
          border: 'none',
          color: '#fff',
          borderRadius: 8,
          padding: '10px 20px',
          fontSize: 14,
          fontWeight: 600,
          cursor: !ready ? 'not-allowed' : 'pointer',
          opacity: !ready ? 0.6 : 1,
        }}
      >
        {ready ? 'Connect via Plaid' : 'Preparing...'}
      </button>
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
      {disabled && value !== 'plaid_poll' && value !== 'news_poll' && (
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

  // Plaid credentials — only used during create
  const [plaidClientId, setPlaidClientId] = useState('')
  const [plaidSecret, setPlaidSecret] = useState('')

  // News credentials and config
  const [newsApiKey, setNewsApiKey] = useState('')
  const [newsCategories, setNewsCategories] = useState('')
  const [newsSources, setNewsSources] = useState('')

  // Reset connector-specific state when the connector type changes (create mode only)
  useEffect(() => {
    if (isEditing) return
    setPlaidClientId('')
    setPlaidSecret('')
    setNewsApiKey('')
    setNewsCategories('')
    setNewsSources('')
  }, [sourceType]) // eslint-disable-line react-hooks/exhaustive-deps

  // Plaid linked banks — loaded during edit
  const [plaidItems, setPlaidItems] = useState<PlaidItem[]>([])
  const [loadingItems, setLoadingItems] = useState(false)
  const [disconnecting, setDisconnecting] = useState<string | null>(null)

  // Plaid Link state — used during edit to add a new bank
  const [linkToken, setLinkToken] = useState<string | null>(null)
  const [addingBank, setAddingBank] = useState(false)
  const [addBankError, setAddBankError] = useState<string | null>(null)

  // General form state
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  // Load existing connection for edit
  useEffect(() => {
    if (!isEditing || !editingId) return
    let mounted = true

    const load = async () => {
      try {
        const conn = await getSourceConnection(editingId)
        if (!mounted) return
        setExistingConn(conn)
        setSourceType(conn.sourceType)
        setConnectionName(conn.connectionName)
        setSyncScheduled(conn.syncScheduled)
        setSyncAdhoc(conn.syncAdhoc)
        setSyncSchedule(conn.syncSchedule ?? '0 2 * * *')
        if (conn.sourceType === 'plaid_poll') {
          setLoadingItems(true)
          listPlaidItems(editingId)
            .then(items => { if (mounted) setPlaidItems(items) })
            .catch(e => { if (mounted) setLoadError(e instanceof Error ? e.message : 'Failed to load linked banks') })
            .finally(() => { if (mounted) setLoadingItems(false) })
        }
        if (conn.sourceType === 'news_poll') {
          const cfg = (conn.config ?? {}) as Record<string, unknown>
          if (typeof cfg.categories === 'string') setNewsCategories(cfg.categories)
          if (typeof cfg.sources === 'string') setNewsSources(cfg.sources)
        }
      } catch (e) {
        if (mounted) setLoadError(e instanceof Error ? e.message : 'Failed to load connection')
      }
    }
    load()
    return () => { mounted = false }
  }, [editingId, isEditing])

  const handleAddBank = async () => {
    if (!editingId) return
    setAddingBank(true)
    setAddBankError(null)
    try {
      const token = await fetchLinkTokenForConnection(editingId)
      setLinkToken(token)
    } catch (e) {
      setAddBankError(e instanceof Error ? e.message : 'Failed to get link token')
      setAddingBank(false)
    }
  }

  const handleAddBankSuccess = async (publicToken: string) => {
    if (!editingId) return
    try {
      await exchangeTokenForConnection(editingId, publicToken)
      setLinkToken(null)
      const items = await listPlaidItems(editingId)
      setPlaidItems(items)
      setAddingBank(false)
    } catch (e) {
      setAddBankError(e instanceof Error ? e.message : 'Exchange failed')
      setAddingBank(false)
    }
  }

  const handleAddBankExit = () => {
    setLinkToken(null)
    setAddingBank(false)
  }

  const handleDisconnect = async (item: PlaidItem) => {
    if (!editingId) return
    setDisconnecting(item.id)
    try {
      await disconnectPlaidItem(editingId, item.id)
      setPlaidItems(prev => prev.filter(i => i.id !== item.id))
    } catch (e) {
      setAddBankError(e instanceof Error ? e.message : 'Disconnect failed')
    } finally {
      setDisconnecting(null)
    }
  }

  // Save for non-Plaid add or edit of any type
  const handleSave = async () => {
    if (!connectionName.trim()) {
      setSaveError('Connection name is required.')
      return
    }
    if (!isEditing) {
      if (sourceType === 'plaid_poll' && (!plaidClientId.trim() || !plaidSecret.trim())) {
        setSaveError('Plaid Client ID and Secret are required.')
        return
      }
      if (sourceType === 'news_poll') {
        if (!newsApiKey.trim()) {
          setSaveError('NewsAPI key is required.')
          return
        }
        if (!newsCategories.trim()) {
          setSaveError('At least one category is required.')
          return
        }
        try { JSON.parse(newsCategories.trim()) } catch {
          setSaveError('Categories must be a valid JSON array, e.g. ["dmoz/Business/Finance"]')
          return
        }
        if (newsSources.trim()) {
          try { JSON.parse(newsSources.trim()) } catch {
            setSaveError('Sources must be a valid JSON array, e.g. ["nytimes.com"]')
            return
          }
        }
      }
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
          config: existingConn.sourceType === 'news_poll'
            ? { categories: newsCategories.trim(), sources: newsSources.trim() }
            : existingConn.config,
        })
      } else if (sourceType === 'plaid_poll') {
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
          secrets: JSON.stringify({ client_id: plaidClientId.trim(), secret: plaidSecret.trim() }),
        })
      } else if (sourceType === 'news_poll') {
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
          secrets: JSON.stringify({ apiKey: newsApiKey.trim() }),
          config: { categories: newsCategories.trim(), sources: newsSources.trim() },
        })
      } else {
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
            disabled={isEditing}
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
          {!isEditing ? (
            <>
              <span style={sectionLabel}>Plaid API Credentials</span>
              <div style={{ marginBottom: 10 }}>
                <input
                  type="text"
                  value={plaidClientId}
                  onChange={e => setPlaidClientId(e.target.value)}
                  placeholder="client_id"
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
                    marginBottom: 8,
                  }}
                />
                <input
                  type="password"
                  value={plaidSecret}
                  onChange={e => setPlaidSecret(e.target.value)}
                  placeholder="secret"
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
              <div style={{ color: T.textMuted, fontSize: 12 }}>
                Credentials are encrypted and stored securely. You can link bank accounts after saving.
              </div>
            </>
          ) : (
            <>
              <span style={sectionLabel}>Linked Banks</span>
              {loadingItems ? (
                <div style={{ color: T.textMuted, fontSize: 13 }}>Loading...</div>
              ) : plaidItems.length === 0 ? (
                <div style={{ color: T.textMuted, fontSize: 13, marginBottom: 10 }}>
                  No banks linked yet.
                </div>
              ) : (
                <div style={{ marginBottom: 12 }}>
                  {plaidItems.map(item => (
                    <div key={item.id} style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      padding: '8px 12px',
                      background: T.bgRunStrip,
                      border: `1px solid ${T.borderRun}`,
                      borderRadius: 6,
                      marginBottom: 6,
                    }}>
                      <span style={{ fontSize: 13, color: T.textPrimary }}>{item.institutionName}</span>
                      <button
                        onClick={() => handleDisconnect(item)}
                        disabled={disconnecting === item.id}
                        style={{
                          background: 'transparent',
                          border: `1px solid ${T.border}`,
                          color: T.textMuted,
                          borderRadius: 4,
                          padding: '3px 10px',
                          fontSize: 12,
                          cursor: disconnecting === item.id ? 'not-allowed' : 'pointer',
                        }}
                      >
                        {disconnecting === item.id ? 'Removing...' : 'Disconnect'}
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {addBankError && (
                <div style={{ color: T.errorText, fontSize: 12, marginBottom: 8 }}>{addBankError}</div>
              )}

              {linkToken ? (
                <PlaidLinkButton
                  token={linkToken}
                  onSuccess={handleAddBankSuccess}
                  onExit={handleAddBankExit}
                />
              ) : (
                <button
                  onClick={handleAddBank}
                  disabled={addingBank}
                  style={{
                    background: T.accent,
                    border: 'none',
                    color: '#fff',
                    borderRadius: 8,
                    padding: '8px 16px',
                    fontSize: 13,
                    fontWeight: 600,
                    cursor: addingBank ? 'not-allowed' : 'pointer',
                    opacity: addingBank ? 0.6 : 1,
                  }}
                >
                  {addingBank ? 'Preparing...' : '+ Add Bank'}
                </button>
              )}

              <div style={{ background: T.bgRunStrip, border: `1px solid ${T.borderRun}`, borderRadius: 8, padding: '10px 14px', color: T.textSecondary, fontSize: 13, marginTop: 12 }}>
                API Credentials: ••••••• (stored encrypted)
              </div>
            </>
          )}
        </div>
      )}

      {/* Section 3b: News-specific */}
      {sourceType === 'news_poll' && (
        <div style={{ marginBottom: 24 }}>
          {!isEditing && (
            <>
              <span style={sectionLabel}>NewsAPI Key</span>
              <input
                type="password"
                value={newsApiKey}
                onChange={e => setNewsApiKey(e.target.value)}
                placeholder="NewsAPI.ai API key"
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
                  marginBottom: 8,
                }}
              />
              <div style={{ color: T.textMuted, fontSize: 12, marginBottom: 16 }}>
                Key is encrypted and stored securely.
              </div>
            </>
          )}
          {isEditing && (
            <div style={{ background: T.bgRunStrip, border: `1px solid ${T.borderRun}`, borderRadius: 8, padding: '10px 14px', color: T.textSecondary, fontSize: 13, marginBottom: 16 }}>
              API Key: ••••••• (stored encrypted)
            </div>
          )}
          <span style={sectionLabel}>Categories (JSON array of NewsAPI category URIs)</span>
          <input
            type="text"
            value={newsCategories}
            onChange={e => setNewsCategories(e.target.value)}
            placeholder='["dmoz/Business/Finance","dmoz/Computers/Internet"]'
            style={{
              width: '100%',
              background: T.bgInput,
              border: `1px solid ${T.border}`,
              borderRadius: 8,
              padding: '10px 12px',
              color: T.textPrimary,
              fontSize: 13,
              fontFamily: "'SF Mono', 'Fira Code', monospace",
              outline: 'none',
              boxSizing: 'border-box',
              marginBottom: 16,
            }}
          />
          <span style={sectionLabel}>Sources (JSON array of NewsAPI source URIs, optional)</span>
          <input
            type="text"
            value={newsSources}
            onChange={e => setNewsSources(e.target.value)}
            placeholder='["nytimes.com","bbc.co.uk"]'
            style={{
              width: '100%',
              background: T.bgInput,
              border: `1px solid ${T.border}`,
              borderRadius: 8,
              padding: '10px 12px',
              color: T.textPrimary,
              fontSize: 13,
              fontFamily: "'SF Mono', 'Fira Code', monospace",
              outline: 'none',
              boxSizing: 'border-box',
            }}
          />
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
