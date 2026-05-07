import { useState, useCallback } from 'react'
import type { Message, DebugInfo, ToolCall, ApiCallDebug, AttachedFile } from '../types'
import { uploadFile } from '../api'

function extractAttachedFiles(toolCalls: ToolCall[]): AttachedFile[] {
  const seen = new Set<string>()
  const files: AttachedFile[] = []
  const FILE_TOOLS = new Set(['get_document', 'create_document', 'list_documents', 'search_documents'])

  for (const tc of toolCalls) {
    if (!FILE_TOOLS.has(tc.name)) continue
    const result = tc.result as Record<string, unknown>
    // single doc (get_document, create_document) or list (items array)
    const docs: unknown[] = Array.isArray(result.items) ? result.items : [result]
    for (const doc of docs) {
      const d = doc as Record<string, unknown>
      if (!Array.isArray(d.files)) continue
      for (const f of d.files as Array<Record<string, string>>) {
        const path = f.file_path
        if (path && !seen.has(path)) {
          seen.add(path)
          files.push({
            path,
            name: path.split('/').pop() ?? path,
            mimeType: f.file_type ?? 'application/octet-stream',
          })
        }
      }
    }
  }
  return files
}

function makeId() {
  return Math.random().toString(36).slice(2)
}

interface UseChatStreamResult {
  messages: Message[]
  isStreaming: boolean
  sendMessage: (text: string, files: File[], personId: string) => Promise<void>
}

export function useChatStream(): UseChatStreamResult {
  const [messages, setMessages] = useState<Message[]>([])
  const [isStreaming, setIsStreaming] = useState(false)

  const sendMessage = useCallback(async (text: string, files: File[], personId: string) => {
    setIsStreaming(true)

    // Show user message and assistant placeholder immediately so the chat
    // doesn't look frozen while the upload is in progress.
    const userMsgId  = makeId()
    const assistantId = makeId()
    setMessages(prev => [...prev, { id: userMsgId,   role: 'user',      text }])
    setMessages(prev => [...prev, { id: assistantId, role: 'assistant', text: '', streaming: true }])

    try {
      // 1. Upload files — inside try so any failure resets isStreaming
      const filePaths: string[] = []
      for (const file of files) {
        filePaths.push(await uploadFile(file))
      }
      // Patch user message with resolved file paths
      if (filePaths.length > 0) {
        setMessages(prev => prev.map(m =>
          m.id === userMsgId ? { ...m, filePaths } : m
        ))
      }

      // 2. Open SSE stream
      const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ personId, message: text, filePaths }),
      })

      if (!resp.ok) throw new Error(`Server error ${resp.status}`)
      if (!resp.body) throw new Error('No response body')

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      let currentEventType = ''  // persists across chunks

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })

        const lines = buf.split('\n')
        buf = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('event: ')) {
            currentEventType = line.slice(7).trim()
          } else if (line.startsWith('data: ')) {
            const payload = JSON.parse(line.slice(6))

            if (currentEventType === 'token') {
              setMessages(prev => prev.map(m =>
                m.id === assistantId ? { ...m, text: m.text + payload.text } : m
              ))
            } else if (currentEventType === 'done') {
              const debugInfo: DebugInfo = payload.debugInfo
              const attachedFiles = extractAttachedFiles(debugInfo?.toolCalls ?? [])
              setMessages(prev => prev.map(m =>
                m.id === assistantId
                  ? { ...m, text: payload.fullText, streaming: false, debugInfo, attachedFiles }
                  : m
              ))
            } else if (currentEventType === 'error') {
              throw new Error(payload.message)
            }
            currentEventType = ''
          }
        }
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        console.error('[useChatStream] error:', err)
        const msg = (err instanceof Error) ? err.message : String(err)
        setMessages(prev => prev.map(m =>
          m.id === assistantId ? { ...m, text: `Error: ${msg}`, streaming: false } : m
        ))
      }
    } finally {
      setIsStreaming(false)
    }
  }, [])

  return { messages, isStreaming, sendMessage }
}
