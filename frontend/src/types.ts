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
