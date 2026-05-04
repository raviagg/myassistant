import type { Session } from './types'

export async function login(username: string): Promise<Session> {
  const resp = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username }),
  })
  if (resp.status === 404) {
    throw new Error('user_not_found')
  }
  if (!resp.ok) {
    throw new Error(`login failed: ${resp.status}`)
  }
  return resp.json()
}

export async function uploadFile(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const resp = await fetch('/api/files', { method: 'POST', body: form })
  if (!resp.ok) throw new Error(`upload failed: ${resp.status}`)
  const data = await resp.json()
  return data.filePath as string
}
