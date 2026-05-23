import { useState, useEffect } from 'react'
import { T } from '../theme'
import {
  listUnifiedSchemas,
  listSourceSchemas,
  updateUnifiedSchema,
  getUnifiedSchemaData,
  getPersonHouseholds,
  getHousehold,
} from '../api'
import type {
  UnifiedSchema,
  UnifiedFieldDefinition,
  SourceSchemasResponse,
  SourceSchemaGroup,
  SourceTable,
} from '../types'

// ─── Sub-components ───────────────────────────────────────────────────────────

function SourceTableCard({
  table,
  highlightedFields,
}: {
  table: SourceTable
  highlightedFields: Set<string>
}) {
  return (
    <div style={{ background: T.bgCard, borderRadius: 4, padding: '7px 9px', border: `1px solid ${T.border}`, marginBottom: 4 }}>
      <div style={{ color: T.textMuted, fontSize: 10, fontWeight: 600, marginBottom: 4 }}>
        {table.tableName}
        {table.foreignKeys.length > 0 && (
          <span style={{ color: T.textVeryMuted, fontWeight: 400, fontSize: 8, marginLeft: 6 }}>
            → {table.foreignKeys.map(fk => fk.refTable).join(', ')}
          </span>
        )}
      </div>
      <div style={{ fontFamily: 'monospace', fontSize: 9, lineHeight: 1.8 }}>
        {table.columns.map(col => {
          const isHighlighted = highlightedFields.has(col.name)
          return (
            <div
              key={col.name}
              style={{
                background: isHighlighted ? '#2a2000' : 'transparent',
                color: isHighlighted ? '#e8a838' : T.textVeryMuted,
                borderRadius: isHighlighted ? 2 : 0,
                padding: isHighlighted ? '0 4px' : 0,
                borderLeft: isHighlighted ? '2px solid #e8a838' : 'none',
              }}
            >
              {col.name} {col.dataType} {isHighlighted && '✦'}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function SourceGroupPanel({
  group,
  highlightedFields,
}: {
  group: SourceSchemaGroup
  highlightedFields: Set<string>
}) {
  const icon = group.sourceType === 'profile' ? '👤'
    : group.sourceType === 'plaid_poll' ? '🏦'
    : group.sourceType === 'gmail_poll' ? '📧'
    : group.sourceType === 'news_poll'  ? '📰'
    : '📄'

  return (
    <div style={{ marginBottom: 14 }}>
      <div style={{ color: '#e8a838', fontSize: 10, marginBottom: 5, fontWeight: 600 }}>
        {icon} {group.connectionName}
      </div>
      {group.tables.length === 0 ? (
        <div style={{ color: T.textVeryMuted, fontSize: 9, fontStyle: 'italic', padding: '4px 2px' }}>
          No schema data yet — run a sync to populate
        </div>
      ) : (
        group.tables.map(table => (
          <SourceTableCard key={table.tableName} table={table} highlightedFields={highlightedFields} />
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

  return (
    <div style={{ background: '#0d1a0d', borderRadius: 6, padding: 10, border: '1px solid #1e3020', marginBottom: 10 }}>
      <div
        style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: expanded ? 8 : 0, cursor: 'pointer' }}
        onClick={() => setExpanded(e => !e)}
      >
        <div style={{ color: '#4ade80', fontSize: 11, fontWeight: 600 }}>💡 unified.{schema.name}</div>
        <div style={{ background: '#1a3020', borderRadius: 3, padding: '1px 6px', color: '#4ade80', fontSize: 9 }}>
          {schema.status}
        </div>
        {pendingCount > 0 && (
          <div style={{ background: '#e8a838', color: '#000', fontSize: 8, padding: '1px 4px', borderRadius: 2 }}>
            {pendingCount} REVIEW
          </div>
        )}
        <div style={{ marginLeft: 'auto', color: T.textVeryMuted, fontSize: 9 }}>{expanded ? '▾ collapse' : '▸ expand'}</div>
      </div>

      {expanded && (
        <>
          <div style={{ background: '#0f1f0f', borderRadius: 4, overflow: 'hidden', fontSize: 9 }}>
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

          <div style={{ display: 'flex', gap: 5, marginTop: 8, alignItems: 'center' }}>
            {schema.fieldDefinitions.flatMap(f => f.sources.map(s => s.source_table)).filter((v, i, a) => a.indexOf(v) === i).map(src => (
              <span key={src} style={{ background: '#1e2130', borderRadius: 3, padding: '2px 7px', color: '#7c8cf8', fontSize: 9 }}>{src}</span>
            ))}
            <button
              type="button"
              onClick={loadData}
              disabled={loadingData}
              style={{ marginLeft: 'auto', background: '#1e2130', border: 'none', color: '#7c8cf8', fontSize: 9, padding: '2px 7px', borderRadius: 3, cursor: 'pointer' }}
            >
              {loadingData ? 'loading…' : '▶ sample data'}
            </button>
          </div>

          {dataRows && (
            <div style={{ marginTop: 8, background: '#0a0f0a', borderRadius: 4, padding: 8, fontSize: 9, fontFamily: 'monospace', color: T.textVeryMuted, maxHeight: 120, overflow: 'auto' }}>
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
        <div style={{ padding: '4px 10px', color: '#7c8cf8', fontSize: 9, letterSpacing: '.06em', marginBottom: 2 }}>PROFILE</div>
        {sourceSchemas?.profile.tables.map(t => (
          <div key={t.tableName} style={{ padding: '3px 10px', color: T.textVeryMuted }}>{t.tableName}</div>
        ))}

        <div style={{ borderTop: `1px solid ${T.border}`, margin: '7px 0' }} />
        <div style={{ padding: '4px 10px', color: '#e8a838', fontSize: 9, letterSpacing: '.06em', marginBottom: 2 }}>DATA SOURCES</div>
        {sourceSchemas?.sources.map(src => (
          <div
            key={src.sourceConnectionId ?? src.connectionName}
            onClick={() => setSelectedSource(
              selectedSource === (src.sourceConnectionId ?? src.connectionName) ? null : (src.sourceConnectionId ?? src.connectionName)
            )}
            style={{
              padding: '3px 10px',
              color: selectedSource === (src.sourceConnectionId ?? src.connectionName) ? '#fff' : T.textVeryMuted,
              background: selectedSource === (src.sourceConnectionId ?? src.connectionName) ? '#1e2130' : 'transparent',
              borderLeft: selectedSource === (src.sourceConnectionId ?? src.connectionName) ? '2px solid #e8a838' : 'none',
              cursor: 'pointer',
            }}
          >
            {src.sourceType === 'plaid_poll' ? '🏦' : src.sourceType === 'gmail_poll' ? '📧' : src.sourceType === 'news_poll' ? '📰' : '📄'} {src.connectionName}
          </div>
        ))}

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
        <div style={{ color: T.textVeryMuted, fontSize: 9, letterSpacing: '.06em', marginBottom: 10 }}>
          {selectedSource ? 'FOCUSED SOURCE SCHEMA' : 'ALL SOURCE SCHEMAS'}
        </div>

        {/* Always show Profile group */}
        {sourceSchemas && (
          <SourceGroupPanel
            group={sourceSchemas.profile}
            highlightedFields={highlightedSourceFields}
          />
        )}

        {/* Source connection groups */}
        {visibleSources.map(src => (
          <SourceGroupPanel
            key={src.sourceConnectionId ?? src.connectionName}
            group={src}
            highlightedFields={highlightedSourceFields}
          />
        ))}
      </div>

      {/* ── Right panel: Unified Schema View ── */}
      <div style={{ padding: 12, overflowY: 'auto', background: '#0b0f0b' }}>
        <div style={{ color: T.textVeryMuted, fontSize: 9, letterSpacing: '.06em', marginBottom: 10 }}>UNIFIED SCHEMAS</div>

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

        <div style={{ marginTop: 10, border: '1px dashed #1e3020', borderRadius: 6, padding: 8, textAlign: 'center' }}>
          <div style={{ color: T.textVeryMuted, fontSize: 10 }}>+ Ask LLM to propose a new unified schema</div>
        </div>

        {highlightedField && (
          <div style={{ marginTop: 12, background: '#13161f', borderRadius: 4, padding: '7px 10px', fontSize: 9, color: T.textVeryMuted }}>
            <div style={{ marginBottom: 3 }}><span style={{ color: '#e8a838' }}>✦</span> = selected — highlighted in source schemas on left</div>
            <div>Click again to deselect</div>
          </div>
        )}
      </div>
    </div>
  )
}
