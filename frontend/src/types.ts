export interface Session {
  personId: string
  displayName: string
  fullName: string
}

export interface ToolCall {
  name: string
  input: Record<string, unknown>
  result: Record<string, unknown>
}

export interface ResponseBlock {
  type: string
  name?: string
}

export interface ApiCallDebug {
  requestMessages: Array<{ role: string }>
  responseBlocks: ResponseBlock[]
  stats: {
    durationMs: number
    inputTokens: number
    cacheReadTokens: number
    outputTokens: number
  }
}

export interface DebugInfo {
  apiCalls: ApiCallDebug[]
  toolCalls: ToolCall[]
}

export interface AttachedFile {
  path: string
  name: string
  mimeType: string
}

export interface SummarizedTopic {
  topic: string
  summaryText: string
  messageCount: number
}

export interface SegmentInfo {
  topic: string
  messageCount: number
  summarized: boolean
  complete: boolean
  summaryText: string
}

export interface ContextInfo {
  currentTopic: string
  rawTurnCount: number
  summarizedTopics: SummarizedTopic[]
  allSegments: SegmentInfo[]
  newSummariesThisTurn: number
  newSummaries: SummarizedTopic[]
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  text: string
  filePaths?: string[]
  attachedFiles?: AttachedFile[]
  debugInfo?: DebugInfo
  streaming?: boolean
  isSummaryDivider?: true
  summaryData?: SummarizedTopic
}

export interface SourceConnection {
  id: string
  sourceType: string
  connectionName: string
  personId: string | null
  householdId: string | null
  config: Record<string, unknown>
  hasSecrets: boolean
  syncScheduled: boolean
  syncAdhoc: boolean
  syncSchedule: string | null
  nextRunAt: string | null
  lastSyncedAt: string | null
  status: 'active' | 'paused' | 'error'
  createdAt: string
  updatedAt: string
}

export interface SyncRun {
  id: string
  sourceConnectionId: string
  runType: 'scheduled' | 'adhoc' | 're_extract'
  status: 'running' | 'success' | 'warning' | 'failed'
  startedAt: string
  completedAt: string | null
  stats: Record<string, number> | null
  logLines: Array<{ time: string; level: string; msg: string }> | null
}

export interface LatestRuns {
  lastScheduled: SyncRun | null
  lastAdhoc: SyncRun | null
}

// ─── Entity Type Schema types ────────────────────────────────────────────────

export interface EntityTypeFieldDef {
  name: string
  type: 'text' | 'number' | 'date' | 'boolean' | 'file' | 'entity_ref' | string
  mandatory?: boolean
  description?: string
  refEntityType?: string
}

export interface EntityTypeSchema {
  id: string
  domainId: string
  entityType: string
  schemaVersion: number
  isActive: boolean
  connectorManaged: boolean
  description?: string
  fieldDefinitions: EntityTypeFieldDef[]
  mandatoryFields: string[]
  createdAt: string
  updatedAt: string
}

export interface Domain {
  id: string
  name: string
  description: string
  createdAt: string
}

// ─── Unified Schema types ────────────────────────────────────────────────────

export interface FieldSource {
  source_connection_id: string
  source_table: string
  source_field: string
}

export interface UnifiedFieldDefinition {
  name: string
  type: string
  status: 'approved' | 'pending' | 'rejected'
  sources: FieldSource[]
}

export interface UnifiedSchema {
  id: string
  personId?: string
  householdId?: string
  name: string
  description?: string
  status: 'proposed' | 'approved'
  fieldDefinitions: UnifiedFieldDefinition[]
  createdAt: string
  updatedAt: string
}

export interface SourceColumn {
  name: string
  dataType: string
}

export interface ForeignKey {
  column: string
  refTable: string
  refColumn: string
}

export interface SourceTable {
  tableName: string
  columns: SourceColumn[]
  foreignKeys: ForeignKey[]
}

export interface SourceSchemaGroup {
  sourceConnectionId?: string
  sourceType: string
  connectionName: string
  tables: SourceTable[]
}

export interface SourceSchemasResponse {
  profile: SourceSchemaGroup
  sources: SourceSchemaGroup[]
}

export interface UnifiedDataRow {
  sourceConnectionId?: string
  sourceType: string
  fields: Record<string, unknown>
}

export interface UnifiedDataResponse {
  items: UnifiedDataRow[]
  total: number
  limit: number
  offset: number
}
