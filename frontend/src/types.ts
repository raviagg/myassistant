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

export interface ApiCallDebug {
  requestMessages: Array<{ role: string }>
  responseBlocks: unknown[]
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

export interface Message {
  id: string
  role: 'user' | 'assistant'
  text: string
  filePaths?: string[]
  debugInfo?: DebugInfo
  streaming?: boolean
}
