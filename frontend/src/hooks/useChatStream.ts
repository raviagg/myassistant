import { useState, useCallback } from 'react'
import type { Message, DebugInfo, ToolCall, ApiCallDebug } from '../types'
import { uploadFile } from '../api'

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

    // 1. Upload files first
    const filePaths: string[] = []
    for (const file of files) {
      const path = await uploadFile(file)
      filePaths.push(path)
    }

    // 2. Add user message to chat
    const userMsg: Message = {
      id: makeId(),
      role: 'user',
      text,
      filePaths: filePaths.length > 0 ? filePaths : undefined,
    }
    setMessages(prev => [...prev, userMsg])

    // 3. Start assistant message placeholder
    const assistantId = makeId()
    const assistantMsg: Message = { id: assistantId, role: 'assistant', text: '', streaming: true }
    setMessages(prev => [...prev, assistantMsg])

    // 4. Open SSE stream
    try {
      const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ personId, message: text, filePaths }),
      })

      if (!resp.body) throw new Error('No response body')

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })

        const lines = buf.split('\n')
        buf = lines.pop() ?? ''

        let currentEventType = ''
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
              setMessages(prev => prev.map(m =>
                m.id === assistantId
                  ? { ...m, text: payload.fullText, streaming: false, debugInfo }
                  : m
              ))
            }
            currentEventType = ''
          }
        }
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        setMessages(prev => prev.map(m =>
          m.id === assistantId ? { ...m, text: 'Error: could not reach server.', streaming: false } : m
        ))
      }
    } finally {
      setIsStreaming(false)
    }
  }, [])

  return { messages, isStreaming, sendMessage }
}
