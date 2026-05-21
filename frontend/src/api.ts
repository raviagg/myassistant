import type { Session, SourceConnection, SyncRun, LatestRuns } from './types'

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

// ── Finance / Plaid ──────────────────────────────────────────────────────────

export interface PlaidItem {
  id: string
  sourceConnectionId: string
  plaidItemId: string
  institutionName: string
  cursor: string | null
  accessToken: string | null
  createdAt: string
  updatedAt: string
}

/** Fetch a Plaid Link token using the credentials stored on this source_connection. */
export async function fetchLinkTokenForConnection(connectionId: string): Promise<string> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/link-token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
  })
  if (!resp.ok) throw new Error(`link-token failed: ${await resp.text()}`)
  const data = await resp.json()
  return data.linkToken
}

/** Exchange a Plaid public token; creates a plaid.connections row under this source_connection. */
export async function exchangeTokenForConnection(
  connectionId: string,
  publicToken: string,
): Promise<{ plaidItemId: string; institutionName: string }> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/exchange`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ publicToken }),
  })
  if (!resp.ok) throw new Error(`exchange failed: ${await resp.text()}`)
  return resp.json()
}

/** List linked bank items under a source_connection. */
export async function listPlaidItems(connectionId: string): Promise<PlaidItem[]> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/items`)
  if (!resp.ok) throw new Error(`list items failed: ${await resp.text()}`)
  return resp.json()
}

/** Disconnect (delete) one linked bank item. */
export async function disconnectPlaidItem(connectionId: string, itemId: string): Promise<void> {
  const resp = await fetch(`/api/v1/source-connections/${connectionId}/plaid/items/${itemId}`, {
    method: 'DELETE',
  })
  if (!resp.ok) throw new Error(`disconnect failed: ${await resp.text()}`)
}

// ── Source Connections API ───────────────────────────────────────────────────

export async function listSourceConnections(personId: string): Promise<SourceConnection[]> {
  const resp = await fetch(`/api/v1/source-connections?personId=${personId}&limit=100`)
  if (!resp.ok) throw new Error(`list connections failed: ${resp.status}`)
  return ((await resp.json()).items ?? []) as SourceConnection[]
}

export async function getSourceConnection(id: string): Promise<SourceConnection> {
  const resp = await fetch(`/api/v1/source-connections/${id}`)
  if (!resp.ok) throw new Error(`get connection failed: ${resp.status}`)
  return resp.json()
}

export async function createSourceConnection(body: {
  sourceType: string
  connectionName: string
  personId: string
  syncScheduled: boolean
  syncAdhoc: boolean
  syncSchedule?: string
  config?: Record<string, unknown>
}): Promise<SourceConnection> {
  const resp = await fetch('/api/v1/source-connections', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!resp.ok) throw new Error(`create connection failed: ${resp.status}`)
  return resp.json()
}

export async function updateSourceConnection(id: string, body: {
  sourceType: string
  connectionName: string
  personId: string | null
  householdId: string | null
  syncScheduled: boolean
  syncAdhoc: boolean
  syncSchedule: string | null
  config: Record<string, unknown>
}): Promise<SourceConnection> {
  const resp = await fetch(`/api/v1/source-connections/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!resp.ok) throw new Error(`update connection failed: ${resp.status}`)
  return resp.json()
}

export async function deleteSourceConnection(id: string): Promise<void> {
  const resp = await fetch(`/api/v1/source-connections/${id}`, { method: 'DELETE' })
  if (!resp.ok) throw new Error(`delete connection failed: ${resp.status}`)
}

export async function triggerAdhocSync(id: string): Promise<void> {
  const resp = await fetch(`/api/v1/source-connections/${id}/sync`, { method: 'POST' })
  if (!resp.ok) throw new Error(`sync failed: ${resp.status}`)
}

export async function fetchLatestRuns(id: string): Promise<LatestRuns> {
  const resp = await fetch(`/api/v1/source-connections/${id}/runs/latest`)
  if (!resp.ok) throw new Error(`fetch runs failed: ${resp.status}`)
  return resp.json()
}

export async function fetchRunDetail(connId: string, runId: string): Promise<SyncRun> {
  const resp = await fetch(`/api/v1/source-connections/${connId}/runs/${runId}`)
  if (!resp.ok) throw new Error(`fetch run failed: ${resp.status}`)
  return resp.json()
}
