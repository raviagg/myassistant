import { useState } from 'react'
import { T } from '../theme'

// ─── Mock data ────────────────────────────────────────────────────────────────

const MOCK_SOURCE_SCHEMAS = [
  {
    id: 'plaid-chase',
    name: "Ravi's Chase",
    sourceType: 'plaid_poll',
    fields: ['amount', 'date', 'merchant_name'],
  },
  {
    id: 'plaid-vanguard',
    name: "Ravi's Vanguard",
    sourceType: 'plaid_poll',
    fields: ['amount', 'date', 'description'],
  },
]

const MOCK_UNIFIED_ENTITIES = [
  {
    id: 'txn',
    name: 'Transaction',
    mergeType: 'UNION',
    fields: ['amount', 'date', 'merchant_name', 'description'],
  },
]

type ChangeType = 'added' | 'modified' | 'removed'
type Decision = 'accepted' | 'rejected' | null

interface DiffRow {
  field: string
  changeType: ChangeType
  source: string
  decision: Decision
}

const MOCK_DIFF_INITIAL: DiffRow[] = [
  { field: 'merchant_name', changeType: 'added',    source: "Ravi's Chase",    decision: null },
  { field: 'description',   changeType: 'modified', source: "Ravi's Vanguard", decision: null },
  { field: 'category',      changeType: 'removed',  source: "Ravi's Chase",    decision: null },
]

// ─── Types ────────────────────────────────────────────────────────────────────

type UnifiedSubTab = 'view' | 'update'
type WizardStep = 1 | 2 | 3 | 4

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getSourceIcon(sourceType: string): string {
  if (sourceType === 'plaid_poll') return '🏦'
  if (sourceType === 'gmail_poll') return '✉️'
  return '⚙️'
}

function getChangeBadgeStyle(changeType: ChangeType): React.CSSProperties {
  if (changeType === 'added')    return { background: T.successBg, color: T.successText, border: `1px solid ${T.successBorder}` }
  if (changeType === 'modified') return { background: T.warningBg, color: T.warningText, border: `1px solid ${T.warningBorder}` }
  return                                 { background: T.errorBg,   color: T.errorText,   border: `1px solid ${T.errorBorder}` }
}

function getChangeRowStyle(changeType: ChangeType): React.CSSProperties {
  if (changeType === 'added')    return { background: T.successBg }
  if (changeType === 'modified') return { background: T.warningBg }
  return                                 { background: T.errorBg }
}

function getChangeLabel(changeType: ChangeType): string {
  if (changeType === 'added')    return '+ added'
  if (changeType === 'modified') return '~ modified'
  return                                 '- removed'
}

// ─── Lineage Diagram ──────────────────────────────────────────────────────────

function LineageDiagram() {
  // Layout constants
  const W = 560
  const H = 220
  const srcX = 30
  const srcW = 140
  const srcH = 56
  const unifiedX = 370
  const unifiedW = 150
  const unifiedH = 72

  const srcY0 = 40
  const srcY1 = 132
  const unifiedY0 = 74

  const srcMidY = [srcY0 + srcH / 2, srcY1 + srcH / 2]
  const unifiedMidY = unifiedY0 + unifiedH / 2

  return (
    <svg
      width="100%"
      height={H}
      viewBox={`0 0 ${W} ${H}`}
      style={{ display: 'block', maxWidth: W }}
    >
      {/* Source nodes */}
      {MOCK_SOURCE_SCHEMAS.map((src, i) => {
        const y = i === 0 ? srcY0 : srcY1
        return (
          <g key={src.id}>
            <rect
              x={srcX} y={y} width={srcW} height={srcH} rx={6}
              fill={T.bgCard}
              stroke={T.warningBorder}
              strokeWidth={1.5}
            />
            <text x={srcX + 10} y={y + 18} fontSize={10} fill={T.warningText} fontWeight={700}>
              {getSourceIcon(src.sourceType)} {src.name}
            </text>
            <text x={srcX + 10} y={y + 32} fontSize={9} fill={T.textMuted}>
              {src.fields.slice(0, 3).join(', ')}
            </text>
            {/* Arrow line */}
            <line
              x1={srcX + srcW} y1={srcMidY[i]}
              x2={unifiedX - 2}  y2={unifiedMidY}
              stroke={T.border}
              strokeWidth={1.5}
            />
            {/* Arrow head */}
            <polygon
              points={`${unifiedX - 2},${unifiedMidY - 5} ${unifiedX + 8},${unifiedMidY} ${unifiedX - 2},${unifiedMidY + 5}`}
              fill={T.border}
            />
            {/* Badge on line */}
            <rect
              x={(srcX + srcW + unifiedX - 2) / 2 - 28}
              y={((srcMidY[i] + unifiedMidY) / 2) - 9}
              width={56} height={18} rx={4}
              fill={T.accentTint}
              stroke={T.accentBorder}
            />
            <text
              x={(srcX + srcW + unifiedX - 2) / 2}
              y={((srcMidY[i] + unifiedMidY) / 2) + 4}
              fontSize={8}
              fill={T.accentLight}
              textAnchor="middle"
              fontWeight={700}
            >
              UNION
            </text>
          </g>
        )
      })}

      {/* Unified entity node */}
      <rect
        x={unifiedX} y={unifiedY0} width={unifiedW} height={unifiedH} rx={6}
        fill={T.bgCard}
        stroke={T.accentBorder}
        strokeWidth={1.5}
      />
      <text x={unifiedX + 10} y={unifiedY0 + 18} fontSize={10} fill={T.accentLight} fontWeight={700}>
        {MOCK_UNIFIED_ENTITIES[0].name}
      </text>
      <text x={unifiedX + 10} y={unifiedY0 + 32} fontSize={9} fill={T.textMuted}>
        {MOCK_UNIFIED_ENTITIES[0].fields.slice(0, 2).join(', ')}
      </text>
      <text x={unifiedX + 10} y={unifiedY0 + 46} fontSize={9} fill={T.textMuted}>
        {MOCK_UNIFIED_ENTITIES[0].fields.slice(2).join(', ')}
      </text>
      <text x={unifiedX + 10} y={unifiedY0 + 60} fontSize={8} fill={T.textVeryMuted}>
        {MOCK_UNIFIED_ENTITIES[0].mergeType}
      </text>
    </svg>
  )
}

// ─── Current Unified View ─────────────────────────────────────────────────────

interface CurrentViewProps {
  onReviewChanges: () => void
}

function CurrentUnifiedView({ onReviewChanges }: CurrentViewProps) {
  const hasUnreviewed = true

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden' }}>
      {/* Review Changes banner */}
      {hasUnreviewed && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '10px 20px',
          background: T.warningBg,
          borderBottom: `1px solid ${T.warningBorder}`,
          flexShrink: 0,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ color: T.warningText, fontSize: 13, fontWeight: 600 }}>
              ⚠ Unreviewed schema updates
            </span>
            <span style={{ color: T.textMuted, fontSize: 12 }}>
              3 field changes pending review
            </span>
          </div>
          <button
            style={{
              padding: '6px 14px',
              background: T.warningBorder,
              color: T.warningText,
              border: `1px solid ${T.warningBorder}`,
              borderRadius: 6,
              cursor: 'pointer',
              fontSize: 12,
              fontWeight: 600,
            }}
            onClick={onReviewChanges}
          >
            Review Changes →
          </button>
        </div>
      )}

      {/* 3-panel layout */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>

        {/* Left — Source Schemas */}
        <div style={{
          width: 230,
          flexShrink: 0,
          borderRight: `1px solid ${T.border}`,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}>
          <div style={{
            padding: '12px 14px 10px',
            borderBottom: `1px solid ${T.border}`,
            fontSize: 11,
            fontWeight: 700,
            color: T.textSecondary,
            letterSpacing: '0.08em',
            textTransform: 'uppercase' as const,
            flexShrink: 0,
          }}>
            Source Schemas
          </div>
          <div style={{ flex: 1, overflowY: 'auto' as const, padding: '12px 10px', display: 'flex', flexDirection: 'column', gap: 10 }}>
            {MOCK_SOURCE_SCHEMAS.map(src => (
              <div key={src.id} style={{
                background: T.bgCard,
                border: `1px solid ${T.warningBorder}`,
                borderRadius: 8,
                padding: '10px 12px',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
                  <span style={{ fontSize: 14 }}>{getSourceIcon(src.sourceType)}</span>
                  <span style={{ fontSize: 12, fontWeight: 700, color: T.warningText }}>{src.name}</span>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  {src.fields.map(f => (
                    <div key={f} style={{ fontSize: 11, color: T.textMuted, paddingLeft: 4 }}>
                      · {f}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Center — Lineage Diagram */}
        <div style={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}>
          <div style={{
            padding: '12px 16px 10px',
            borderBottom: `1px solid ${T.border}`,
            fontSize: 11,
            fontWeight: 700,
            color: T.textSecondary,
            letterSpacing: '0.08em',
            textTransform: 'uppercase' as const,
            flexShrink: 0,
          }}>
            Schema Lineage
          </div>
          <div style={{ flex: 1, overflow: 'auto', padding: '24px 16px', display: 'flex', alignItems: 'flex-start', justifyContent: 'center' }}>
            <LineageDiagram />
          </div>
        </div>

        {/* Right — Unified Entities */}
        <div style={{
          width: 230,
          flexShrink: 0,
          borderLeft: `1px solid ${T.border}`,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}>
          <div style={{
            padding: '12px 14px 10px',
            borderBottom: `1px solid ${T.border}`,
            fontSize: 11,
            fontWeight: 700,
            color: T.textSecondary,
            letterSpacing: '0.08em',
            textTransform: 'uppercase' as const,
            flexShrink: 0,
          }}>
            Unified Entities
          </div>
          <div style={{ flex: 1, overflowY: 'auto' as const, padding: '12px 10px', display: 'flex', flexDirection: 'column', gap: 10 }}>
            {MOCK_UNIFIED_ENTITIES.map(ent => (
              <div key={ent.id} style={{
                background: T.bgCard,
                border: `1px solid ${T.accentBorder}`,
                borderRadius: 8,
                padding: '10px 12px',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, color: T.accentLight }}>{ent.name}</span>
                  <span style={{
                    fontSize: 9,
                    fontWeight: 700,
                    color: T.accentLight,
                    background: T.accentTint,
                    border: `1px solid ${T.accentBorder}`,
                    borderRadius: 4,
                    padding: '1px 5px',
                  }}>
                    {ent.mergeType}
                  </span>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  {ent.fields.map(f => (
                    <div key={f} style={{ fontSize: 11, color: T.textMuted, paddingLeft: 4 }}>
                      · {f}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>

      </div>
    </div>
  )
}

// ─── Update Unified View (Wizard) ─────────────────────────────────────────────

function UpdateUnifiedView() {
  const [step, setStep] = useState<WizardStep>(1)
  const [diff, setDiff] = useState<DiffRow[]>(MOCK_DIFF_INITIAL)
  const [materializing, setMaterializing] = useState(false)
  const [materialized, setMaterialized] = useState(false)

  const steps: { id: WizardStep; label: string }[] = [
    { id: 1, label: 'Review Changes' },
    { id: 2, label: 'Accept / Reject Fields' },
    { id: 3, label: 'Preview' },
    { id: 4, label: 'Approve & Materialize' },
  ]

  const setDecision = (field: string, decision: Decision) => {
    setDiff(prev => prev.map(r => r.field === field ? { ...r, decision } : r))
  }

  const acceptAll = () => {
    setDiff(prev => prev.map(r => ({ ...r, decision: 'accepted' })))
  }

  const undecided = diff.filter(r => r.decision === null)
  const acceptedCount = diff.filter(r => r.decision === 'accepted').length
  const rejectedCount = diff.filter(r => r.decision === 'rejected').length

  const handleMaterialize = () => {
    setMaterializing(true)
    setTimeout(() => {
      setMaterializing(false)
      setMaterialized(true)
    }, 1400)
  }

  return (
    <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>

      {/* Sidebar */}
      <div style={{
        width: 180,
        flexShrink: 0,
        borderRight: `1px solid ${T.border}`,
        display: 'flex',
        flexDirection: 'column',
        padding: '16px 0',
        gap: 2,
        background: T.bgCard,
      }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: T.textVeryMuted, letterSpacing: '0.1em', textTransform: 'uppercase' as const, padding: '0 14px 10px' }}>
          Wizard Steps
        </div>
        {steps.map(s => {
          const isActive = step === s.id
          const isDone = step > s.id
          return (
            <button
              key={s.id}
              onClick={() => setStep(s.id)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px 14px',
                border: 'none',
                background: isActive ? T.accentTint : 'transparent',
                borderLeft: isActive ? `3px solid ${T.accent}` : '3px solid transparent',
                color: isActive ? T.accentLight : isDone ? T.successText : T.textSecondary,
                cursor: 'pointer',
                fontSize: 12,
                fontWeight: isActive ? 600 : 400,
                textAlign: 'left' as const,
                width: '100%',
              }}
            >
              <span style={{
                width: 18, height: 18, borderRadius: '50%',
                background: isActive ? T.accent : isDone ? T.successBorder : T.border,
                color: isActive ? '#fff' : isDone ? T.successText : T.textMuted,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 9, fontWeight: 700, flexShrink: 0,
              }}>
                {isDone ? '✓' : s.id}
              </span>
              {s.label}
            </button>
          )
        })}
      </div>

      {/* Main content */}
      <div style={{ flex: 1, minWidth: 0, overflow: 'auto', padding: 24 }}>

        {/* Step 1 — Review Changes */}
        {step === 1 && (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
              <div>
                <div style={{ fontSize: 16, fontWeight: 700, color: T.textPrimary, marginBottom: 4 }}>Review Changes</div>
                <div style={{ fontSize: 12, color: T.textMuted }}>Field-level changes detected from new source data</div>
              </div>
              <button
                onClick={acceptAll}
                style={{
                  padding: '7px 16px',
                  background: T.accentTint,
                  border: `1px solid ${T.accentBorder}`,
                  borderRadius: 6,
                  color: T.accentLight,
                  cursor: 'pointer',
                  fontSize: 12,
                  fontWeight: 600,
                }}
              >
                Accept All
              </button>
            </div>

            {/* Diff table */}
            <div style={{ border: `1px solid ${T.border}`, borderRadius: 8, overflow: 'hidden' }}>
              {/* Header */}
              <div style={{
                display: 'grid',
                gridTemplateColumns: '1fr 110px 160px 130px',
                padding: '8px 14px',
                background: T.bgCard,
                borderBottom: `1px solid ${T.border}`,
                fontSize: 11,
                fontWeight: 700,
                color: T.textMuted,
                textTransform: 'uppercase' as const,
                letterSpacing: '0.07em',
              }}>
                <span>Field</span>
                <span>Change</span>
                <span>Source</span>
                <span>Decision</span>
              </div>
              {diff.map((row, idx) => (
                <div
                  key={row.field}
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 110px 160px 130px',
                    padding: '10px 14px',
                    alignItems: 'center',
                    ...getChangeRowStyle(row.changeType),
                    borderBottom: idx < diff.length - 1 ? `1px solid ${T.border}` : 'none',
                  }}
                >
                  <span style={{ fontSize: 13, fontWeight: 600, color: T.textPrimary, fontFamily: 'monospace' }}>
                    {row.field}
                  </span>
                  <span style={{
                    display: 'inline-block',
                    padding: '2px 7px',
                    borderRadius: 4,
                    fontSize: 11,
                    fontWeight: 700,
                    ...getChangeBadgeStyle(row.changeType),
                  }}>
                    {getChangeLabel(row.changeType)}
                  </span>
                  <span style={{ fontSize: 12, color: T.textSecondary }}>{row.source}</span>
                  <div style={{ display: 'flex', gap: 6 }}>
                    {row.decision === null ? (
                      <>
                        <button
                          onClick={() => setDecision(row.field, 'accepted')}
                          style={{
                            padding: '4px 10px', border: `1px solid ${T.successBorder}`,
                            borderRadius: 5, background: 'transparent', color: T.successText,
                            cursor: 'pointer', fontSize: 11, fontWeight: 600,
                          }}
                        >
                          Accept
                        </button>
                        <button
                          onClick={() => setDecision(row.field, 'rejected')}
                          style={{
                            padding: '4px 10px', border: `1px solid ${T.errorBorder}`,
                            borderRadius: 5, background: 'transparent', color: T.errorText,
                            cursor: 'pointer', fontSize: 11, fontWeight: 600,
                          }}
                        >
                          Reject
                        </button>
                      </>
                    ) : (
                      <span style={{
                        fontSize: 11, fontWeight: 700,
                        color: row.decision === 'accepted' ? T.successText : T.errorText,
                      }}>
                        {row.decision === 'accepted' ? '✓ Accepted' : '✗ Rejected'}
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>

            <div style={{ marginTop: 20, display: 'flex', justifyContent: 'flex-end' }}>
              <button
                onClick={() => setStep(2)}
                style={{
                  padding: '8px 20px', background: T.accent, border: 'none',
                  borderRadius: 7, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600,
                }}
              >
                Next: Accept / Reject →
              </button>
            </div>
          </div>
        )}

        {/* Step 2 — Accept / Reject Fields */}
        {step === 2 && (
          <div>
            <div style={{ marginBottom: 18 }}>
              <div style={{ fontSize: 16, fontWeight: 700, color: T.textPrimary, marginBottom: 4 }}>Accept / Reject Fields</div>
              <div style={{ fontSize: 12, color: T.textMuted }}>
                {undecided.length > 0
                  ? `${undecided.length} field${undecided.length > 1 ? 's' : ''} pending decision`
                  : 'All fields decided'}
              </div>
            </div>

            {undecided.length === 0 ? (
              <div style={{
                padding: 24, borderRadius: 8, background: T.successBg, border: `1px solid ${T.successBorder}`,
                color: T.successText, fontSize: 13, fontWeight: 600, textAlign: 'center' as const,
              }}>
                All fields have been decided. Click Next to preview the result.
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {undecided.map(row => (
                  <div key={row.field} style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: '14px 18px',
                    background: T.bgCard,
                    border: `1px solid ${T.border}`,
                    borderRadius: 8,
                  }}>
                    <div>
                      <div style={{ fontSize: 14, fontWeight: 700, color: T.textPrimary, fontFamily: 'monospace', marginBottom: 4 }}>
                        {row.field}
                      </div>
                      <div style={{ fontSize: 11, color: T.textMuted }}>{row.source} · {getChangeLabel(row.changeType)}</div>
                    </div>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <span style={{ fontSize: 10, color: T.textVeryMuted, marginRight: 4 }}>[A] / [R]</span>
                      <button
                        onClick={() => setDecision(row.field, 'accepted')}
                        style={{
                          padding: '7px 16px', background: T.successBg,
                          border: `1px solid ${T.successBorder}`, borderRadius: 6,
                          color: T.successText, cursor: 'pointer', fontSize: 12, fontWeight: 600,
                        }}
                      >
                        Accept
                      </button>
                      <button
                        onClick={() => setDecision(row.field, 'rejected')}
                        style={{
                          padding: '7px 16px', background: T.errorBg,
                          border: `1px solid ${T.errorBorder}`, borderRadius: 6,
                          color: T.errorText, cursor: 'pointer', fontSize: 12, fontWeight: 600,
                        }}
                      >
                        Reject
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}

            <div style={{ marginTop: 20, display: 'flex', justifyContent: 'space-between' }}>
              <button
                onClick={() => setStep(1)}
                style={{
                  padding: '8px 18px', background: 'transparent', border: `1px solid ${T.border}`,
                  borderRadius: 7, color: T.textSecondary, cursor: 'pointer', fontSize: 13,
                }}
              >
                ← Back
              </button>
              <button
                onClick={() => setStep(3)}
                style={{
                  padding: '8px 20px', background: T.accent, border: 'none',
                  borderRadius: 7, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600,
                }}
              >
                Next: Preview →
              </button>
            </div>
          </div>
        )}

        {/* Step 3 — Preview */}
        {step === 3 && (
          <div>
            <div style={{ marginBottom: 18 }}>
              <div style={{ fontSize: 16, fontWeight: 700, color: T.textPrimary, marginBottom: 4 }}>Preview</div>
              <div style={{ fontSize: 12, color: T.textMuted }}>Unified schema after applying accepted changes</div>
            </div>

            <div style={{ border: `1px solid ${T.border}`, borderRadius: 8, overflow: 'hidden' }}>
              {/* Header */}
              <div style={{
                display: 'grid', gridTemplateColumns: '1fr 100px 200px',
                padding: '8px 14px',
                background: T.bgCard,
                borderBottom: `1px solid ${T.border}`,
                fontSize: 11, fontWeight: 700, color: T.textMuted,
                textTransform: 'uppercase' as const, letterSpacing: '0.07em',
              }}>
                <span>Field</span>
                <span>Type</span>
                <span>Source</span>
              </div>

              {/* Base unified fields always present */}
              {['amount', 'date'].map((field, idx) => (
                <div key={field} style={{
                  display: 'grid', gridTemplateColumns: '1fr 100px 200px',
                  padding: '10px 14px', alignItems: 'center',
                  background: idx % 2 === 0 ? T.bgPage : T.bgCard,
                  borderBottom: `1px solid ${T.border}`,
                }}>
                  <span style={{ fontSize: 13, color: T.textPrimary, fontFamily: 'monospace' }}>{field}</span>
                  <span style={{ fontSize: 12, color: T.textMuted }}>text</span>
                  <span style={{ fontSize: 12, color: T.textSecondary }}>All sources</span>
                </div>
              ))}

              {/* Accepted diff fields */}
              {diff.filter(r => r.decision === 'accepted').map((row, idx) => (
                <div key={row.field} style={{
                  display: 'grid', gridTemplateColumns: '1fr 100px 200px',
                  padding: '10px 14px', alignItems: 'center',
                  background: (idx + 2) % 2 === 0 ? T.bgPage : T.bgCard,
                  borderBottom: `1px solid ${T.border}`,
                }}>
                  <span style={{ fontSize: 13, color: T.successText, fontFamily: 'monospace' }}>
                    {row.field} <span style={{ fontSize: 10, color: T.successText }}>+ accepted</span>
                  </span>
                  <span style={{ fontSize: 12, color: T.textMuted }}>text</span>
                  <span style={{ fontSize: 12, color: T.textSecondary }}>{row.source}</span>
                </div>
              ))}

              {diff.filter(r => r.decision === 'accepted').length === 0 && (
                <div style={{ padding: '14px', fontSize: 12, color: T.textMuted, fontStyle: 'italic' }}>
                  No additional fields accepted
                </div>
              )}
            </div>

            <div style={{ marginTop: 20, display: 'flex', justifyContent: 'space-between' }}>
              <button
                onClick={() => setStep(2)}
                style={{
                  padding: '8px 18px', background: 'transparent', border: `1px solid ${T.border}`,
                  borderRadius: 7, color: T.textSecondary, cursor: 'pointer', fontSize: 13,
                }}
              >
                ← Back
              </button>
              <button
                onClick={() => setStep(4)}
                style={{
                  padding: '8px 20px', background: T.accent, border: 'none',
                  borderRadius: 7, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600,
                }}
              >
                Next: Approve & Materialize →
              </button>
            </div>
          </div>
        )}

        {/* Step 4 — Approve & Materialize */}
        {step === 4 && (
          <div>
            <div style={{ marginBottom: 20 }}>
              <div style={{ fontSize: 16, fontWeight: 700, color: T.textPrimary, marginBottom: 4 }}>Approve & Materialize</div>
              <div style={{ fontSize: 12, color: T.textMuted }}>Review your decisions and materialize the unified schema</div>
            </div>

            {/* Summary cards */}
            <div style={{ display: 'flex', gap: 14, marginBottom: 24 }}>
              <div style={{
                flex: 1, padding: '16px 20px', borderRadius: 8,
                background: T.successBg, border: `1px solid ${T.successBorder}`,
              }}>
                <div style={{ fontSize: 28, fontWeight: 800, color: T.successText }}>{acceptedCount}</div>
                <div style={{ fontSize: 12, color: T.textSecondary, marginTop: 2 }}>Fields Accepted</div>
              </div>
              <div style={{
                flex: 1, padding: '16px 20px', borderRadius: 8,
                background: T.errorBg, border: `1px solid ${T.errorBorder}`,
              }}>
                <div style={{ fontSize: 28, fontWeight: 800, color: T.errorText }}>{rejectedCount}</div>
                <div style={{ fontSize: 12, color: T.textSecondary, marginTop: 2 }}>Fields Rejected</div>
              </div>
              <div style={{
                flex: 1, padding: '16px 20px', borderRadius: 8,
                background: T.warningBg, border: `1px solid ${T.warningBorder}`,
              }}>
                <div style={{ fontSize: 28, fontWeight: 800, color: T.warningText }}>{undecided.length}</div>
                <div style={{ fontSize: 12, color: T.textSecondary, marginTop: 2 }}>Pending</div>
              </div>
            </div>

            {materialized ? (
              <div style={{
                padding: '20px 24px', borderRadius: 8,
                background: T.successBg, border: `1px solid ${T.successBorder}`,
                textAlign: 'center' as const,
              }}>
                <div style={{ fontSize: 24, marginBottom: 8 }}>✓</div>
                <div style={{ fontSize: 15, fontWeight: 700, color: T.successText, marginBottom: 4 }}>
                  Schema Materialized
                </div>
                <div style={{ fontSize: 12, color: T.textSecondary }}>
                  The unified schema has been updated and is now active.
                </div>
              </div>
            ) : (
              <>
                <div style={{
                  padding: '14px 18px', borderRadius: 8, marginBottom: 20,
                  background: T.infoBg, border: `1px solid ${T.infoBorder}`,
                  fontSize: 12, color: T.infoText,
                }}>
                  This will apply {acceptedCount} accepted change{acceptedCount !== 1 ? 's' : ''} to the unified Transaction schema.
                  {undecided.length > 0 && ` ${undecided.length} pending field${undecided.length > 1 ? 's' : ''} will be skipped.`}
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <button
                    onClick={() => setStep(3)}
                    style={{
                      padding: '8px 18px', background: 'transparent', border: `1px solid ${T.border}`,
                      borderRadius: 7, color: T.textSecondary, cursor: 'pointer', fontSize: 13,
                    }}
                  >
                    ← Back
                  </button>
                  <button
                    onClick={handleMaterialize}
                    disabled={materializing}
                    style={{
                      padding: '10px 28px',
                      background: materializing ? T.accentTint : T.accent,
                      border: `1px solid ${materializing ? T.accentBorder : T.accent}`,
                      borderRadius: 7,
                      color: materializing ? T.accentLight : '#fff',
                      cursor: materializing ? 'not-allowed' : 'pointer',
                      fontSize: 14,
                      fontWeight: 700,
                      opacity: materializing ? 0.7 : 1,
                    }}
                  >
                    {materializing ? 'Materializing...' : 'Approve & Materialize'}
                  </button>
                </div>
              </>
            )}
          </div>
        )}

      </div>
    </div>
  )
}

// ─── Root Component ───────────────────────────────────────────────────────────

export default function UnifiedViewBuilderTab() {
  const [subTab, setSubTab] = useState<UnifiedSubTab>('view')

  const tabs: { id: UnifiedSubTab; label: string }[] = [
    { id: 'view',   label: 'Current Unified View' },
    { id: 'update', label: 'Update Unified View' },
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden', background: T.bgPage }}>

      {/* Sub-tab bar */}
      <div style={{
        display: 'flex',
        flexDirection: 'row',
        background: T.bgCard,
        borderBottom: `1px solid ${T.border}`,
        height: 40,
        alignItems: 'stretch',
        flexShrink: 0,
      }}>
        {tabs.map(tab => {
          const isActive = subTab === tab.id
          return (
            <button
              key={tab.id}
              style={{
                height: 40,
                padding: '0 16px',
                border: 'none',
                background: isActive ? T.accentTint : 'transparent',
                color: isActive ? T.accentLight : T.textSecondary,
                cursor: 'pointer',
                fontSize: 12,
                fontWeight: isActive ? 600 : 500,
                transition: 'background 0.15s, color 0.15s',
              }}
              onMouseEnter={e => {
                if (!isActive) {
                  (e.currentTarget as HTMLButtonElement).style.background = T.border
                  ;(e.currentTarget as HTMLButtonElement).style.color = T.textPrimary
                }
              }}
              onMouseLeave={e => {
                if (!isActive) {
                  (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
                  ;(e.currentTarget as HTMLButtonElement).style.color = T.textSecondary
                }
              }}
              onClick={() => setSubTab(tab.id)}
            >
              {tab.label}
            </button>
          )
        })}
      </div>

      {/* Content */}
      <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        {subTab === 'view' && (
          <CurrentUnifiedView onReviewChanges={() => setSubTab('update')} />
        )}
        {subTab === 'update' && (
          <UpdateUnifiedView />
        )}
      </div>

    </div>
  )
}
