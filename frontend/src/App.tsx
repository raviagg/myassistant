import { useState } from 'react'
import LoginScreen from './components/LoginScreen'
import ChatScreen from './components/ChatScreen'
import type { Session } from './types'

export default function App() {
  const [session, setSession] = useState<Session | null>(null)

  if (!session) {
    return <LoginScreen onLogin={setSession} />
  }
  return <ChatScreen session={session} onLogout={() => setSession(null)} />
}
