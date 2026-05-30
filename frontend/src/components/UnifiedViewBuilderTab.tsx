import { useState, useEffect, useRef } from 'react'
import { T } from '../theme'
import {
  listEntityTypeSchemas,
  listSourceSchemas,
  listDomains,
  getPersonHouseholds,
  getHousehold,
  fetchSourceTableSample,
} from '../api'
import type {
  EntityTypeSchema,
  EntityTypeFieldDef,
  Domain,
  SourceSchemasResponse,
} from '../types'

// ─── Color helpers ────────────────────────────────────────────────────────────

function domainColor(name: string): string {
  const map: Record<string, string> = {
    finance:          '#f59e0b',
    health:           '#10b981',
    news:             '#3b82f6',
    employment:       '#8b5cf6',
    todo:             '#f97316',
    household:        '#ec4899',
    personal_details: '#06b6d4',
  }
  return map[name] ?? '#64748b'
}

function sourceColor(sourceType: string): string {
  switch (sourceType) {
    case 'plaid_poll': return '#f59e0b'
    case 'chatbot':    return '#7c8cf8'
    case 'news_poll':  return '#10b981'
    case 'gmail_poll': return '#3b82f6'
    default:           return '#64748b'
  }
}

function fieldTypeColor(type: string): string {
  switch (type) {
    case 'text':       return '#4a8aaa'
    case 'number':     return '#a878d8'
    case 'date':       return '#4aba80'
    case 'boolean':    return '#ea8040'
    case 'entity_ref': return '#e8b840'
    case 'file':       return '#7890e8'
    default:           return '#64748b'
  }
}

// ─── Types ────────────────────────────────────────────────────────────────────

interface Connector {
  sourceConnectionId: string
  sourceType: string
  connectionName: string
}

interface Arrow {
  id: string
  fromX: number
  fromY: number
  toX: number
  toY: number
}

// ─── EntityTypeSchemaCard ─────────────────────────────────────────────────────

function EntityTypeSchemaCard({
  schema,
  domainName,
  connectors,
  cardRef,
  fieldRef,
  personId,
  householdId,
}: {
  schema: EntityTypeSchema
  domainName: string
  connectors: Connector[]
  cardRef: (el: HTMLDivElement | null) => void
  fieldRef: (fieldName: string, el: HTMLDivElement | null) => void
  personId?: string
  householdId?: string
}) {
  const [expanded, setExpanded] = useState(false)
  const [rows, setRows] = useState<Record<string, unknown>[] | null>(null)
  const [loadingRows, setLoadingRows] = useState(false)
  const [rowsError, setRowsError] = useState<string | null>(null)

  const dColor = domainColor(domainName)
  const primaryConnector = connectors[0]
  const accentColor = primaryConnector ? sourceColor(primaryConnector.sourceType) : dColor

  async function toggleSample() {
    if (expanded) { setExpanded(false); return }
    setExpanded(true)
    if (rows !== null) return
    setLoadingRows(true)
    setRowsError(null)
    try {
      const sourceType = primaryConnector?.sourceType ?? 'chatbot'
      const tableName = `${sourceType}/${schema.entityType}`
      const result = await fetchSourceTableSample({
        sourceType,
        tableName,
        sourceConnectionId: primaryConnector?.sourceConnectionId || undefined,
        personId,
        householdId,
        limit: 5,
      })
      setRows(result)
    } catch (e) {
      setRowsError(e instanceof Error ? e.message : 'Failed to load sample rows')
    } finally {
      setLoadingRows(false)
    }
  }

  const colNames = schema.fieldDefinitions.map((f: EntityTypeFieldDef) => f.name)

  return (
    <div
      ref={cardRef}
      style={{
        background: '#0c1220',
        border: `1px solid ${accentColor}33`,
        borderTop: `3px solid ${accentColor}`,
        borderRadius: 8,
        overflow: 'hidden',
      }}
    >
      {/* Header */}
      <div
        onClick={toggleSample}
        style={{ padding: '10px 12px', cursor: 'pointer' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
          <span style={{ color: accentColor, fontFamily: 'monospace', fontSize: 12, fontWeight: 700, flex: 1 }}>
            {schema.entityType}
          </span>
          {schema.connectorManaged && (
            <span title="connector-managed — read only" style={{ fontSize: 10 }}>🔒</span>
          )}
          <span style={{
            background: dColor + '22',
            border: `1px solid ${dColor}44`,
            color: dColor,
            fontSize: 8,
            padding: '1px 6px',
            borderRadius: 3,
            fontWeight: 600,
            letterSpacing: '.04em',
          }}>
            {domainName}
          </span>
          <span style={{ color: accentColor + '55', fontSize: 9 }}>
            {loadingRows ? '…' : expanded ? '▴' : '▾ rows'}
          </span>
        </div>

        {/* Connector badges */}
        {connectors.length > 0 && (
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 8 }}>
            {connectors.map(c => {
              const sc = sourceColor(c.sourceType)
              return (
                <span key={c.sourceConnectionId} style={{
                  background: sc + '18',
                  border: `1px solid ${sc}44`,
                  color: sc,
                  fontSize: 8,
                  padding: '1px 6px',
                  borderRadius: 3,
                  fontFamily: 'monospace',
                  fontWeight: 500,
                }}>
                  {c.connectionName}
                </span>
              )
            })}
          </div>
        )}

        {/* Field list */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
          {schema.fieldDefinitions.map((field: EntityTypeFieldDef) => {
            const isRef = field.type === 'entity_ref'
            const fc = fieldTypeColor(field.type)
            return (
              <div
                key={field.name}
                ref={isRef ? (el: HTMLDivElement | null) => fieldRef(field.name, el) : undefined}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 5,
                  padding: '2px 0',
                  paddingLeft: isRef ? 6 : 0,
                  borderLeft: isRef ? `2px solid ${fc}66` : 'none',
                }}
              >
                <span style={{
                  fontFamily: 'monospace',
                  fontSize: 9,
                  color: isRef ? fc : '#6080a0',
                  fontWeight: isRef ? 600 : 400,
                  flex: 1,
                }}>
                  {field.name}
                  {field.mandatory && <span style={{ color: '#e87060', marginLeft: 2 }}>*</span>}
                </span>
                <span style={{
                  background: fc + '18',
                  border: `1px solid ${fc}33`,
                  color: fc,
                  fontSize: 8,
                  padding: '0 4px',
                  borderRadius: 2,
                  fontFamily: 'monospace',
                }}>
                  {field.type}
                </span>
                {isRef && field.refEntityType && (
                  <span style={{ color: fc + '77', fontSize: 8, fontFamily: 'monospace' }}>
                    → {field.refEntityType}
                  </span>
                )}
              </div>
            )
          })}
        </div>
      </div>

      {/* Sample rows panel */}
      {expanded && (
        <div style={{ borderTop: `1px solid ${accentColor}22`, background: accentColor + '08' }}>
          {rowsError && (
            <div style={{ padding: '6px 12px', color: '#f87171', fontSize: 9, fontFamily: 'monospace' }}>{rowsError}</div>
          )}
          {loadingRows && (
            <div style={{ padding: '6px 12px', color: accentColor + '66', fontSize: 9 }}>loading…</div>
          )}
          {rows && rows.length === 0 && (
            <div style={{ padding: '6px 12px', color: accentColor + '44', fontSize: 9, fontStyle: 'italic' }}>no rows found</div>
          )}
          {rows && rows.length > 0 && (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 9, fontFamily: 'monospace' }}>
                <thead>
                  <tr>
                    {colNames.map((col: string) => (
                      <th key={col} style={{
                        padding: '4px 8px', textAlign: 'left',
                        color: accentColor + '88', fontWeight: 600,
                        borderBottom: `1px solid ${accentColor}22`,
                        whiteSpace: 'nowrap',
                      }}>{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, i) => (
                    <tr key={i} style={{ background: i % 2 === 0 ? accentColor + '08' : 'transparent' }}>
                      {colNames.map((col: string) => {
                        const val = row[col]
                        const display = val == null ? '' : typeof val === 'object' ? JSON.stringify(val) : String(val)
                        return (
                          <td key={col} style={{
                            padding: '3px 8px',
                            color: accentColor + 'bb',
                            maxWidth: 140,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
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

// ─── Main Component ───────────────────────────────────────────────────────────

export default function UnifiedViewBuilderTab({ personId, displayName }: { personId: string; displayName: string }) {
  const [schemas, setSchemas]           = useState<EntityTypeSchema[]>([])
  const [domains, setDomains]           = useState<Domain[]>([])
  const [sourceSchemas, setSourceSchemas] = useState<SourceSchemasResponse | null>(null)
  const [selectedDomain, setSelectedDomain] = useState<string | null>(null)
  const [loading, setLoading]           = useState(true)
  const [error, setError]               = useState<string | null>(null)
  const [households, setHouseholds]     = useState<Array<{ id: string; name: string }>>([])
  const [pivotHouseholdId, setPivotHouseholdId] = useState<string | null>(null)
  const [arrows, setArrows]             = useState<Arrow[]>([])

  const containerRef = useRef<HTMLDivElement>(null)
  const cardRefs     = useRef<Map<string, HTMLDivElement>>(new Map())
  const fieldRefs    = useRef<Map<string, HTMLDivElement>>(new Map())

  useEffect(() => {
    getPersonHouseholds(personId)
      .then(resp => Promise.all(resp.householdIds.map(hid => getHousehold(hid))))
      .then(houses => setHouseholds(houses.map(h => ({ id: h.id, name: h.name }))))
      .catch(() => {})
  }, [personId])

  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [schemasResp, domainsResp, sourceSchemasResp] = await Promise.all([
          listEntityTypeSchemas(true),
          listDomains(),
          pivotHouseholdId
            ? listSourceSchemas(undefined, pivotHouseholdId)
            : listSourceSchemas(personId),
        ])
        setSchemas(schemasResp.items)
        setDomains(domainsResp.items)
        setSourceSchemas(sourceSchemasResp)
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to load')
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [personId, pivotHouseholdId])

  // Recompute SVG FK arrows after schemas/domain change
  useEffect(() => {
    if (loading || !containerRef.current) return
    const container = containerRef.current

    const id = requestAnimationFrame(() => {
      const containerRect = container.getBoundingClientRect()
      const newArrows: Arrow[] = []

      for (const [key, fieldEl] of fieldRefs.current) {
        const dotIdx = key.indexOf('.')
        const entityType = key.slice(0, dotIdx)
        const fieldName  = key.slice(dotIdx + 1)

        const schema = schemas.find(s => s.entityType === entityType)
        if (!schema) continue
        const field = schema.fieldDefinitions.find((f: EntityTypeFieldDef) => f.name === fieldName)
        if (!field || field.type !== 'entity_ref' || !field.refEntityType) continue

        const targetCard = cardRefs.current.get(field.refEntityType)
        if (!targetCard) continue

        const fieldRect  = fieldEl.getBoundingClientRect()
        const targetRect = targetCard.getBoundingClientRect()
        if (!fieldRect.width || !targetRect.width) continue

        const fromX = fieldRect.right  - containerRect.left
        const fromY = fieldRect.top    + fieldRect.height / 2 - containerRect.top
        const toX   = targetRect.left  - containerRect.left
        const toY   = targetRect.top   + 24 - containerRect.top

        newArrows.push({ id: key, fromX, fromY, toX, toY })
      }

      setArrows(newArrows)
    })

    return () => cancelAnimationFrame(id)
  }, [schemas, selectedDomain, loading])

  const domainNameMap = Object.fromEntries(domains.map(d => [d.id, d.name]))

  function connectorsFor(schema: EntityTypeSchema): Connector[] {
    if (!sourceSchemas) return []
    return sourceSchemas.sources
      .filter(g => g.tables.some(t => {
        const parts = t.tableName.split('/')
        return parts[parts.length - 1] === schema.entityType
      }))
      .map(g => ({
        sourceConnectionId: g.sourceConnectionId ?? '',
        sourceType: g.sourceType,
        connectionName: g.connectionName,
      }))
  }

  const visibleSchemas = selectedDomain
    ? schemas.filter(s => domainNameMap[s.domainId] === selectedDomain)
    : schemas

  const domainCounts = domains
    .map(d => ({ name: d.name, count: schemas.filter(s => s.domainId === d.id).length }))
    .filter(d => d.count > 0)

  const effectivePersonId    = pivotHouseholdId ? undefined : personId
  const effectiveHouseholdId = pivotHouseholdId ?? undefined

  if (loading) return (
    <div style={{ padding: 24, color: T.textVeryMuted, fontSize: 12 }}>Loading schema view…</div>
  )
  if (error) return (
    <div style={{ padding: 24, color: '#f87171', fontSize: 12 }}>Error: {error}</div>
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#0f1117' }}>

      {/* ── Pivot picker ── */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 14px', background: '#13161f', borderBottom: `1px solid ${T.border}`, flexShrink: 0 }}>
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
          <button key={h.id} type="button" onClick={() => setPivotHouseholdId(h.id)} style={{
            background: pivotHouseholdId === h.id ? '#7c8cf8' : '#1e2130',
            border: 'none', borderRadius: 4, padding: '4px 12px',
            color: pivotHouseholdId === h.id ? '#fff' : T.textVeryMuted,
            fontSize: 11, fontWeight: 600, cursor: 'pointer',
          }}>
            🏠 {h.name}
          </button>
        ))}
        <div style={{ marginLeft: 'auto', color: T.textVeryMuted, fontSize: 9, fontStyle: 'italic' }}>
          {pivotHouseholdId ? 'household view' : 'person view'}
        </div>
      </div>

      {/* ── Main row ── */}
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>

        {/* ── Domain sidebar ── */}
        <div style={{ width: 150, background: '#13161f', borderRight: `1px solid ${T.border}`, padding: '10px 0', overflowY: 'auto', flexShrink: 0 }}>
          <div style={{ padding: '4px 12px', color: '#4a5a7a', fontSize: 9, letterSpacing: '.06em', marginBottom: 4 }}>DOMAINS</div>

          <div
            onClick={() => setSelectedDomain(null)}
            style={{
              padding: '5px 12px', cursor: 'pointer',
              color: !selectedDomain ? '#e2e8f0' : '#4a5a7a',
              background: !selectedDomain ? '#1e2535' : 'transparent',
              borderLeft: !selectedDomain ? '2px solid #7c8cf8' : '2px solid transparent',
              fontSize: 10,
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}
          >
            <span>All</span>
            <span style={{ color: '#4a5a7a', fontSize: 9 }}>{schemas.length}</span>
          </div>

          {domainCounts.map(({ name, count }) => {
            const dc = domainColor(name)
            const isActive = selectedDomain === name
            return (
              <div
                key={name}
                onClick={() => setSelectedDomain(isActive ? null : name)}
                style={{
                  padding: '5px 12px', cursor: 'pointer',
                  color: isActive ? dc : '#4a5a7a',
                  background: isActive ? dc + '18' : 'transparent',
                  borderLeft: isActive ? `2px solid ${dc}` : '2px solid transparent',
                  fontSize: 10, fontWeight: isActive ? 600 : 400,
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                }}
              >
                <span>{name}</span>
                <span style={{ color: '#4a5a7a', fontSize: 9 }}>{count}</span>
              </div>
            )
          })}
        </div>

        {/* ── Schema cards area ── */}
        <div style={{ flex: 1, overflow: 'auto' }}>
          <div ref={containerRef} style={{ position: 'relative', padding: 16 }}>

            {visibleSchemas.length === 0 ? (
              <div style={{ color: T.textVeryMuted, fontSize: 12, padding: 8 }}>No schemas for this domain.</div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 14 }}>
                {visibleSchemas.map(schema => {
                  const connectors = connectorsFor(schema)
                  return (
                    <EntityTypeSchemaCard
                      key={schema.id}
                      schema={schema}
                      domainName={domainNameMap[schema.domainId] ?? 'unknown'}
                      connectors={connectors}
                      cardRef={el => {
                        if (el) cardRefs.current.set(schema.entityType, el)
                        else cardRefs.current.delete(schema.entityType)
                      }}
                      fieldRef={(fieldName, el) => {
                        const key = `${schema.entityType}.${fieldName}`
                        if (el) fieldRefs.current.set(key, el)
                        else fieldRefs.current.delete(key)
                      }}
                      personId={effectivePersonId}
                      householdId={effectiveHouseholdId}
                    />
                  )
                })}
              </div>
            )}

            {/* SVG FK arrows */}
            {arrows.length > 0 && (
              <svg
                style={{
                  position: 'absolute',
                  top: 0, left: 0,
                  width: '100%', height: '100%',
                  pointerEvents: 'none',
                  overflow: 'visible',
                }}
              >
                <defs>
                  <marker id="fk-arrow" markerWidth="6" markerHeight="5" refX="5" refY="2.5" orient="auto">
                    <polygon points="0 0, 6 2.5, 0 5" fill="#e8b84077" />
                  </marker>
                </defs>
                {arrows.map(arrow => {
                  const dx = Math.max(40, Math.abs(arrow.toX - arrow.fromX) * 0.45)
                  const d = `M ${arrow.fromX} ${arrow.fromY} C ${arrow.fromX + dx} ${arrow.fromY}, ${arrow.toX - dx} ${arrow.toY}, ${arrow.toX} ${arrow.toY}`
                  return (
                    <path
                      key={arrow.id}
                      d={d}
                      stroke="#e8b84055"
                      strokeWidth="1.5"
                      fill="none"
                      strokeDasharray="5 3"
                      markerEnd="url(#fk-arrow)"
                    />
                  )
                })}
              </svg>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
