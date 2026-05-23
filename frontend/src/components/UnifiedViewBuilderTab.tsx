import { useState, useEffect } from 'react'
import { T } from '../theme'
import {
  listUnifiedSchemas,
  listSourceSchemas,
  updateUnifiedSchema,
  getUnifiedSchemaData,
  getPersonHouseholds,
  getHousehold,
  fetchSourceTableSample,
} from '../api'
import type {
  UnifiedSchema,
  UnifiedFieldDefinition,
  SourceSchemasResponse,
  SourceSchemaGroup,
  SourceTable,
} from '../types'

// ─── Source-type color palette ────────────────────────────────────────────────

function sourceColor(sourceType: string): string {
  switch (sourceType) {
    case 'plaid_poll': return '#f59e0b'
    case 'chatbot':    return '#8b5cf6'
    case 'news_poll':  return '#10b981'
    case 'gmail_poll': return '#3b82f6'
    case 'profile':    return '#7c8cf8'
    default:           return '#64748b'
  }
}

function fieldSuffix(dataType: string): string {
  if (dataType === 'entity_ref') return '+'
  if (dataType.includes('[]'))   return '[]'
  return ''
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function SourceTableCard({
  table,
  highlightedFields,
  accentColor,
  sourceType,
  sourceConnectionId,
  personId,
  householdId,
}: {
  table: SourceTable
  highlightedFields: Set<string>
  accentColor: string
  sourceType: string
  sourceConnectionId?: string
  personId?: string
  householdId?: string
}) {
  const [expanded, setExpanded] = useState(false)
  const [rows, setRows] = useState<Record<string, unknown>[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [sampleError, setSampleError] = useState<string | null>(null)

  const hasHighlight = table.columns.some(c => highlightedFields.has(c.name))
  const c = accentColor  // 7-char '#rrggbb' — append 2-char alpha for 8-digit hex

  async function toggleSample() {
    if (expanded) { setExpanded(false); return }
    setExpanded(true)
    if (rows !== null) return  // already fetched
    setLoading(true)
    setSampleError(null)
    try {
      const result = await fetchSourceTableSample({
        sourceType,
        tableName: table.tableName,
        sourceConnectionId,
        personId,
        householdId,
        limit: 5,
      })
      setRows(result)
    } catch (e) {
      setSampleError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setLoading(false)
    }
  }

  const colNames = table.columns.map(col => col.name)

  return (
    <div
      style={{
        background: hasHighlight ? c + '1a' : c + '0b',
        borderRadius: 7,
        border: `1px solid ${hasHighlight ? c + '66' : c + '30'}`,
        borderLeft: `3px solid ${hasHighlight ? c : c + '55'}`,
        marginBottom: 6,
        overflow: 'hidden',
      }}
    >
      {/* Header row — click to expand sample */}
      <div
        onClick={toggleSample}
        style={{ padding: '8px 10px', cursor: 'pointer' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 7 }}>
          <div style={{
            color: hasHighlight ? '#ffffff' : c + 'cc',
            fontSize: 11, fontWeight: 700, fontFamily: 'monospace', letterSpacing: '-.01em',
            flex: 1,
          }}>
            {table.tableName}
          </div>
          <div style={{ color: c + '66', fontSize: 9 }}>
            {loading ? '…' : expanded ? '▴' : '▾ rows'}
          </div>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {table.columns.map(col => {
            const isHighlighted = highlightedFields.has(col.name)
            const suffix = fieldSuffix(col.dataType)
            return (
              <span
                key={col.name}
                style={{
                  background: isHighlighted ? c + '33' : c + '15',
                  color: isHighlighted ? c : c + '99',
                  border: `1px solid ${isHighlighted ? c + '77' : c + '33'}`,
                  borderRadius: 4, padding: '2px 7px',
                  fontSize: 9, fontFamily: 'monospace',
                  fontWeight: isHighlighted ? 700 : 400,
                }}
              >
                {col.name}{suffix}
              </span>
            )
          })}
        </div>
        {table.foreignKeys.length > 0 && (
          <div style={{ marginTop: 5, fontSize: 8, color: c + '44', fontFamily: 'monospace' }}>
            → {table.foreignKeys.map(fk => fk.refTable).join(', ')}
          </div>
        )}
      </div>

      {/* Sample rows panel */}
      {expanded && (
        <div style={{ borderTop: `1px solid ${c}22`, background: c + '08' }}>
          {sampleError && (
            <div style={{ padding: '6px 10px', color: '#f87171', fontSize: 9, fontFamily: 'monospace' }}>{sampleError}</div>
          )}
          {loading && (
            <div style={{ padding: '6px 10px', color: c + '66', fontSize: 9 }}>loading…</div>
          )}
          {rows && rows.length === 0 && (
            <div style={{ padding: '6px 10px', color: c + '44', fontSize: 9, fontStyle: 'italic' }}>no rows found</div>
          )}
          {rows && rows.length > 0 && (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 9, fontFamily: 'monospace' }}>
                <thead>
                  <tr>
                    {colNames.map(col => (
                      <th key={col} style={{
                        padding: '4px 8px', textAlign: 'left',
                        color: c + '99', fontWeight: 600, borderBottom: `1px solid ${c}22`,
                        whiteSpace: 'nowrap',
                      }}>{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, i) => (
                    <tr key={i} style={{ background: i % 2 === 0 ? c + '08' : 'transparent' }}>
                      {colNames.map(col => {
                        const val = row[col]
                        const display = val == null ? '' : typeof val === 'object' ? JSON.stringify(val) : String(val)
                        return (
                          <td key={col} style={{
                            padding: '3px 8px', color: c + 'cc',
                            maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          }} title={display}>{display}</td>
                        )
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function SourceGroupPanel({
  group,
  highlightedFields,
  personId,
  householdId,
}: {
  group: SourceSchemaGroup
  highlightedFields: Set<string>
  personId?: string
  householdId?: string
}) {
  const color = sourceColor(group.sourceType)

  return (
    <div style={{ marginBottom: 20 }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 7,
        marginBottom: 8,
        paddingBottom: 5,
        borderBottom: `1px solid ${color}22`,
      }}>
        <span style={{
          display: 'inline-block',
          width: 8, height: 8,
          borderRadius: '50%',
          background: color,
          flexShrink: 0,
        }} />
        <span style={{ color, fontSize: 10, fontWeight: 700, letterSpacing: '.08em' }}>
          {group.connectionName.toUpperCase()}
        </span>
      </div>
      {group.tables.length === 0 ? (
        <div style={{ color: color + '44', fontSize: 9, fontStyle: 'italic', padding: '4px 6px' }}>
          No schema data yet — run a sync to populate
        </div>
      ) : (
        group.tables.map(table => (
          <SourceTableCard
            key={table.tableName}
            table={table}
            highlightedFields={highlightedFields}
            accentColor={color}
            sourceType={group.sourceType}
            sourceConnectionId={group.sourceConnectionId ?? undefined}
            personId={personId}
            householdId={householdId}
          />
        ))
      )}
    </div>
  )
}

function UnifiedFieldRow({
  field,
  isHighlighted,
  onClick,
  onAccept,
  onReject,
}: {
  field: UnifiedFieldDefinition
  isHighlighted: boolean
  onClick: () => void
  onAccept?: () => void
  onReject?: () => void
}) {
  const isPending = field.status === 'pending'
  const isRejected = field.status === 'rejected'
  return (
    <div
      onClick={onClick}
      style={{
        display: 'grid',
        gridTemplateColumns: '80px 1fr',
        borderBottom: '1px solid #1a2a1a',
        padding: '5px 7px',
        background: isHighlighted ? '#2a2000' : isPending ? '#1a1500' : 'transparent',
        borderLeft: isHighlighted ? '3px solid #e8a838' : 'none',
        cursor: 'pointer',
        opacity: isRejected ? 0.4 : 1,
      }}
    >
      <div style={{ color: isHighlighted ? '#e8a838' : isPending ? '#e8a838' : '#4ade80', fontFamily: 'monospace', fontWeight: isHighlighted ? 600 : 400 }}>
        {field.name} {isHighlighted && '✦'} {isPending && '★'}
      </div>
      <div>
        {isPending ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ color: T.textVeryMuted, fontSize: 9, fontFamily: 'monospace' }}>
              {field.sources.map(s => s.source_field).join(' · ')}
            </span>
            {onAccept && (
              <button type="button" onClick={e => { e.stopPropagation(); onAccept() }} style={{ background: '#4ade80', color: '#000', fontSize: 8, padding: '1px 4px', borderRadius: 2, border: 'none', cursor: 'pointer' }}>✓</button>
            )}
            {onReject && (
              <button type="button" onClick={e => { e.stopPropagation(); onReject() }} style={{ background: '#555', color: '#000', fontSize: 8, padding: '1px 4px', borderRadius: 2, border: 'none', cursor: 'pointer' }}>✕</button>
            )}
            <span style={{ background: '#e8a838', color: '#000', fontSize: 8, padding: '1px 4px', borderRadius: 2 }}>REVIEW</span>
          </div>
        ) : isHighlighted ? (
          <div style={{ lineHeight: 1.8 }}>
            {field.sources.map(s => (
              <div key={s.source_connection_id + s.source_field} style={{ color: '#e8a838', fontFamily: 'monospace', fontSize: 8 }}>
                ← {s.source_table}.{s.source_field}
              </div>
            ))}
          </div>
        ) : (
          <div style={{ color: T.textVeryMuted, fontSize: 9 }}>
            {field.sources.map(s => s.source_field).join(' · ') || 'no mapping'}
          </div>
        )}
      </div>
    </div>
  )
}

function UnifiedSchemaCard({
  schema,
  highlightedField,
  onFieldClick,
  onFieldUpdate,
}: {
  schema: UnifiedSchema
  highlightedField: string | null
  onFieldClick: (fieldName: string | null) => void
  onFieldUpdate: (schema: UnifiedSchema) => void
}) {
  const [expanded, setExpanded] = useState(true)
  const [loadingData, setLoadingData] = useState(false)
  const [dataRows, setDataRows] = useState<unknown[] | null>(null)
  const [patchError, setPatchError] = useState<string | null>(null)
  const [dataError, setDataError] = useState<string | null>(null)

  const pendingCount = schema.fieldDefinitions.filter(f => f.status === 'pending').length

  async function patchField(fieldName: string, newStatus: 'approved' | 'rejected') {
    setPatchError(null)
    try {
      const newDefs = schema.fieldDefinitions.map(f =>
        f.name === fieldName ? { ...f, status: newStatus } : f
      )
      const updated = await updateUnifiedSchema(schema.id, { fieldDefinitions: newDefs })
      onFieldUpdate(updated)
    } catch (e) {
      setPatchError(e instanceof Error ? e.message : 'Update failed')
    }
  }

  async function loadData() {
    setLoadingData(true)
    setDataError(null)
    try {
      const result = await getUnifiedSchemaData(schema.id, 10, 0)
      setDataRows(result.items)
    } catch (e) {
      setDataError(e instanceof Error ? e.message : 'Failed to load data')
    } finally {
      setLoadingData(false)
    }
  }

  const sourceTables = schema.fieldDefinitions
    .flatMap(f => f.sources.map(s => s.source_table))
    .filter((v, i, a) => a.indexOf(v) === i)

  return (
    <div style={{ background: '#0c1220', borderRadius: 8, padding: 12, border: '1px solid #1a2540', marginBottom: 10 }}>
      <div
        style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: expanded ? 10 : 0, cursor: 'pointer' }}
        onClick={() => setExpanded(e => !e)}
      >
        <div style={{ color: '#4ade80', fontSize: 12, fontWeight: 700, fontFamily: 'monospace' }}>unified.{schema.name}</div>
        <div style={{
          background: schema.status === 'approved' ? '#10b98122' : '#f59e0b22',
          border: `1px solid ${schema.status === 'approved' ? '#10b98166' : '#f59e0b66'}`,
          borderRadius: 4,
          padding: '1px 7px',
          color: schema.status === 'approved' ? '#10b981' : '#f59e0b',
          fontSize: 8,
          fontWeight: 700,
          letterSpacing: '.06em',
        }}>
          {schema.status.toUpperCase()}
        </div>
        {pendingCount > 0 && (
          <div style={{ background: '#f59e0b', color: '#000', fontSize: 8, padding: '2px 6px', borderRadius: 4, fontWeight: 700 }}>
            {pendingCount} REVIEW
          </div>
        )}
        <div style={{ marginLeft: 'auto', color: '#2a3a5a', fontSize: 9 }}>{expanded ? '▾' : '▸'}</div>
      </div>

      {expanded && (
        <>
          <div style={{ background: '#080e1c', borderRadius: 5, overflow: 'hidden', fontSize: 9, border: '1px solid #111e35' }}>
            {schema.fieldDefinitions.map(field => (
              <UnifiedFieldRow
                key={field.name}
                field={field}
                isHighlighted={highlightedField === field.name}
                onClick={() => onFieldClick(highlightedField === field.name ? null : field.name)}
                onAccept={field.status === 'pending' ? () => patchField(field.name, 'approved') : undefined}
                onReject={field.status === 'pending' ? () => patchField(field.name, 'rejected') : undefined}
              />
            ))}
          </div>

          {patchError && (
            <div style={{ color: '#f87171', fontSize: 9, padding: '4px 7px' }}>{patchError}</div>
          )}

          <div style={{ display: 'flex', gap: 5, marginTop: 9, alignItems: 'center', flexWrap: 'wrap' }}>
            {sourceTables.map(src => (
              <span key={src} style={{ background: '#0e1830', border: '1px solid #1e2e50', borderRadius: 4, padding: '2px 8px', color: '#4a6aa0', fontSize: 8, fontFamily: 'monospace' }}>{src}</span>
            ))}
            <button
              type="button"
              onClick={loadData}
              disabled={loadingData}
              style={{ marginLeft: 'auto', background: '#0e1830', border: '1px solid #1e2e50', color: '#4a6aa0', fontSize: 9, padding: '3px 9px', borderRadius: 4, cursor: 'pointer' }}
            >
              {loadingData ? 'loading…' : '▶ sample data'}
            </button>
          </div>

          {dataRows && (
            <div style={{ marginTop: 8, background: '#060b14', borderRadius: 5, padding: 10, fontSize: 9, fontFamily: 'monospace', color: '#3a4a6a', maxHeight: 140, overflow: 'auto', border: '1px solid #111e35' }}>
              {dataRows.length === 0 ? 'no data' : JSON.stringify(dataRows.slice(0, 3), null, 2)}
            </div>
          )}

          {dataError && (
            <div style={{ marginTop: 6, color: '#f87171', fontSize: 9, fontFamily: 'monospace' }}>{dataError}</div>
          )}
        </>
      )}
    </div>
  )
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function UnifiedViewBuilderTab({ personId, displayName }: { personId: string; displayName: string }) {
  const [schemas, setSchemas]               = useState<UnifiedSchema[]>([])
  const [sourceSchemas, setSourceSchemas]   = useState<SourceSchemasResponse | null>(null)
  const [selectedSource, setSelectedSource] = useState<string | null>(null)
  const [highlightedField, setHighlightedField] = useState<string | null>(null)
  const [loading, setLoading]               = useState(true)
  const [error, setError]                   = useState<string | null>(null)
  const [households, setHouseholds]         = useState<Array<{ id: string; name: string }>>([])
  const [pivotHouseholdId, setPivotHouseholdId] = useState<string | null>(null)

  // Fetch households once on mount so the pivot picker can show them
  useEffect(() => {
    getPersonHouseholds(personId)
      .then(resp => Promise.all(resp.householdIds.map(hid => getHousehold(hid))))
      .then(houses => setHouseholds(houses.map(h => ({ id: h.id, name: h.name }))))
      .catch(() => { /* households not critical */ })
  }, [personId])

  // Reload data whenever the pivot changes
  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      setSelectedSource(null)
      try {
        const [schemasResp, sourceSchemasResp] = await Promise.all([
          pivotHouseholdId
            ? listUnifiedSchemas(undefined, pivotHouseholdId)
            : listUnifiedSchemas(personId),
          pivotHouseholdId
            ? listSourceSchemas(undefined, pivotHouseholdId)
            : listSourceSchemas(personId),
        ])
        setSchemas(schemasResp.items)
        setSourceSchemas(sourceSchemasResp)
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to load')
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [personId, pivotHouseholdId])

  // Compute which source fields should be highlighted based on the selected unified field
  const highlightedSourceFields = (() => {
    if (!highlightedField) return new Set<string>()
    const allFields = schemas.flatMap(s => s.fieldDefinitions)
    const match = allFields.find(f => f.name === highlightedField)
    if (!match) return new Set<string>()
    return new Set(match.sources.map(s => s.source_field))
  })()

  const visibleSources = selectedSource
    ? sourceSchemas?.sources.filter(s => s.sourceConnectionId === selectedSource || s.connectionName === selectedSource) ?? []
    : sourceSchemas?.sources ?? []

  if (loading) return (
    <div style={{ padding: 24, color: T.textVeryMuted, fontSize: 12 }}>Loading unified view…</div>
  )

  if (error) return (
    <div style={{ padding: 24, color: '#f87171', fontSize: 12 }}>Error: {error}</div>
  )

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '130px 1fr 1fr', gridTemplateRows: 'auto 1fr', height: '100%', background: '#0f1117' }}>

      {/* ── Pivot picker (spans all 3 columns) ── */}
      <div style={{ gridColumn: '1 / -1', display: 'flex', alignItems: 'center', gap: 8, padding: '7px 14px', background: '#13161f', borderBottom: `1px solid ${T.border}` }}>
        <button
          type="button"
          onClick={() => setPivotHouseholdId(null)}
          style={{
            background: !pivotHouseholdId ? '#7c8cf8' : '#1e2130',
            border: 'none', borderRadius: 4, padding: '4px 12px',
            color: !pivotHouseholdId ? '#fff' : T.textVeryMuted,
            fontSize: 11, fontWeight: 600, cursor: 'pointer',
          }}
        >
          👤 {displayName}
        </button>
        {households.map(h => (
          <button
            key={h.id}
            type="button"
            onClick={() => setPivotHouseholdId(h.id)}
            style={{
              background: pivotHouseholdId === h.id ? '#7c8cf8' : '#1e2130',
              border: 'none', borderRadius: 4, padding: '4px 12px',
              color: pivotHouseholdId === h.id ? '#fff' : T.textVeryMuted,
              fontSize: 11, fontWeight: 600, cursor: 'pointer',
            }}
          >
            🏠 {h.name}
          </button>
        ))}
        <div style={{ marginLeft: 'auto', color: T.textVeryMuted, fontSize: 9, fontStyle: 'italic' }}>
          {pivotHouseholdId ? 'household view' : 'person view · full picture'}
        </div>
      </div>

      {/* ── Sidebar ── */}
      <div style={{ background: '#13161f', borderRight: `1px solid ${T.border}`, padding: '10px 0', fontSize: 10, overflowY: 'auto' }}>
        <div style={{ padding: '4px 10px', color: '#4a5a7a', fontSize: 9, letterSpacing: '.06em', marginBottom: 2 }}>DATA SOURCES</div>
        {sourceSchemas?.sources.map(src => {
          const srcKey = src.sourceConnectionId ?? src.connectionName
          const isActive = selectedSource === srcKey
          const color = sourceColor(src.sourceType)
          const icon = src.sourceType === 'plaid_poll' ? '🏦' : src.sourceType === 'gmail_poll' ? '📧' : src.sourceType === 'news_poll' ? '📰' : src.sourceType === 'chatbot' ? '🤖' : '📄'
          return (
            <div
              key={srcKey}
              onClick={() => setSelectedSource(isActive ? null : srcKey)}
              style={{
                padding: '4px 10px',
                color: isActive ? color : '#4a5a7a',
                background: isActive ? color + '15' : 'transparent',
                borderLeft: `2px solid ${isActive ? color : 'transparent'}`,
                cursor: 'pointer',
                fontSize: 10,
                fontWeight: isActive ? 600 : 400,
              }}
            >
              {icon} {src.connectionName}
            </div>
          )
        })}

        {!selectedSource && (
          <>
            <div style={{ borderTop: `1px solid ${T.border}`, margin: '7px 0' }} />
            <div style={{ padding: '4px 10px', background: '#131a13', borderLeft: '2px solid #7c8cf8' }}>
              <div style={{ color: '#7c8cf8', fontSize: 9 }}>← full picture</div>
              <div style={{ color: T.textVeryMuted, fontSize: 8, marginTop: 2 }}>click a source<br />to narrow view</div>
            </div>
          </>
        )}
      </div>

      {/* ── Left panel: Source Schema Browser ── */}
      <div style={{ borderRight: `1px solid ${T.border}`, padding: 12, overflowY: 'auto', background: '#0f1117' }}>
        <div style={{ color: '#2a3a5a', fontSize: 9, letterSpacing: '.08em', fontWeight: 700, marginBottom: 12 }}>
          {selectedSource ? 'FOCUSED SOURCE SCHEMA' : 'ALL SOURCE SCHEMAS'}
        </div>

        {visibleSources.map(src => (
          <SourceGroupPanel
            key={src.sourceConnectionId ?? src.connectionName}
            group={src}
            highlightedFields={highlightedSourceFields}
            personId={pivotHouseholdId ? undefined : personId}
            householdId={pivotHouseholdId ?? undefined}
          />
        ))}
      </div>

      {/* ── Right panel: Unified Schema View ── */}
      <div style={{ padding: 12, overflowY: 'auto', background: '#0b0f0b' }}>
        <div style={{ color: '#2a3a5a', fontSize: 9, letterSpacing: '.08em', fontWeight: 700, marginBottom: 12 }}>UNIFIED SCHEMAS</div>

        {schemas.length === 0 ? (
          <div style={{ color: T.textVeryMuted, fontSize: 11, padding: 8 }}>No unified schemas yet.</div>
        ) : (
          schemas.map(schema => (
            <UnifiedSchemaCard
              key={schema.id}
              schema={schema}
              highlightedField={highlightedField}
              onFieldClick={setHighlightedField}
              onFieldUpdate={updated => setSchemas(prev => prev.map(s => s.id === updated.id ? updated : s))}
            />
          ))
        )}

        <div style={{ marginTop: 10, border: '1px dashed #1a2540', borderRadius: 8, padding: '10px 12px', textAlign: 'center' }}>
          <div style={{ color: '#2a3a5a', fontSize: 10 }}>+ Ask LLM to propose a new unified schema</div>
        </div>

        {highlightedField && (
          <div style={{ marginTop: 12, background: '#0c1220', borderRadius: 6, padding: '8px 10px', fontSize: 9, color: '#2a3a5a', border: '1px solid #1a2540' }}>
            <div style={{ marginBottom: 3 }}><span style={{ color: '#4ade80' }}>✦</span> {highlightedField} — highlighted in source schemas on left</div>
            <div>Click again to deselect</div>
          </div>
        )}
      </div>
    </div>
  )
}
