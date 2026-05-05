import { useState, FormEvent } from 'react'
import { login } from '../api'
import type { Session } from '../types'

interface Props {
  onLogin: (session: Session) => void
}

export default function LoginScreen({ onLogin }: Props) {
  const [username, setUsername] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const session = await login(username.trim())
      onLogin(session)
    } catch (err: unknown) {
      setError(err instanceof Error && err.message === 'user_not_found'
        ? 'Username not found. Please try again.'
        : 'Something went wrong. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={styles.outer}>
      <div style={styles.card}>
        <div style={styles.logo}>🤖</div>
        <h1 style={styles.title}>Personal Assistant</h1>
        <p style={styles.subtitle}>Enter your username to continue</p>
        <form onSubmit={handleSubmit} style={styles.form}>
          <input
            style={styles.input}
            type="text"
            placeholder="Username (e.g. raaggarw)"
            value={username}
            onChange={e => setUsername(e.target.value)}
            disabled={loading}
            autoFocus
          />
          <button style={styles.btn} type="submit" disabled={loading || !username.trim()}>
            {loading ? 'Checking…' : 'Continue →'}
          </button>
          {error && <p style={styles.error}>{error}</p>}
        </form>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  outer:    { display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', background: 'var(--bg-base)' },
  card:     { background: 'var(--bg-surface)', border: '1px solid var(--border)', borderRadius: 12, padding: '48px 32px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 20, width: 360 },
  logo:     { fontSize: 36, width: 60, height: 60, background: 'linear-gradient(135deg,#4a6fa5,#7c5cbf)', borderRadius: 14, display: 'flex', alignItems: 'center', justifyContent: 'center' },
  title:    { fontSize: 20, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-muted)', textAlign: 'center' },
  form:     { width: '100%', display: 'flex', flexDirection: 'column', gap: 12 },
  input:    { width: '100%', padding: '10px 14px', background: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: 8, color: 'var(--text-primary)', fontSize: 14 },
  btn:      { width: '100%', padding: 11, background: 'linear-gradient(135deg,#4a6fa5,#7c5cbf)', borderRadius: 8, color: '#fff', fontSize: 14, fontWeight: 600 },
  error:    { fontSize: 12, color: 'var(--error)', textAlign: 'center' },
}
