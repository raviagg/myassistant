import { useEffect, useRef } from 'react'
import MessageBubble from './MessageBubble'
import SummaryDivider from './SummaryDivider'
import type { Message } from '../types'

interface Props {
  messages: Message[]
}

export default function MessageList({ messages }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  return (
    <div style={styles.list}>
      {messages.map(msg => {
        if (msg.isSummaryDivider && msg.summaryData) {
          return <SummaryDivider key={msg.id} data={msg.summaryData} />
        }
        return (
          <div key={msg.id} style={{ display: 'flex', justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
            <MessageBubble message={msg} />
          </div>
        )
      })}
      <div ref={bottomRef} />
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  list: { flex: 1, overflowY: 'auto', padding: '16px', display: 'flex', flexDirection: 'column', gap: 14 },
}
