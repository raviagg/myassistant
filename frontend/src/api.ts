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

export interface PlaidConnectionFields {
  item_id: string
  institution_id?: string
  institution_name: string
  sync_cursor: string
  last_synced_at?: string
}

export interface PlaidConnection {
  entityInstanceId: string
  schemaId: string
  fields: PlaidConnectionFields
}

export interface BankAccountFields {
  account_id: string
  item_id: string
  name: string
  official_name?: string
  type: string
  subtype?: string
  mask?: string
  current_balance?: number
  available_balance?: number
  iso_currency_code?: string
  institution_name?: string
}

export interface BankAccount {
  entityInstanceId: string
  fields: BankAccountFields
}

export async function fetchLinkToken(personId: string): Promise<string> {
  const resp = await fetch('/api/v1/plaid/link-token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ personId }),
  })
  if (!resp.ok) throw new Error(`link-token failed: ${resp.status}`)
  const data = await resp.json()
  return data.linkToken as string
}

export async function exchangeToken(personId: string, publicToken: string): Promise<void> {
  const resp = await fetch('/api/v1/plaid/exchange', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ personId, publicToken }),
  })
  if (!resp.ok) throw new Error(`exchange failed: ${resp.status}`)
}

export async function listPlaidConnections(personId: string): Promise<PlaidConnection[]> {
  const resp = await fetch(
    `/api/v1/facts/current?personId=${personId}&entityType=plaid_connection&limit=50`
  )
  if (!resp.ok) throw new Error(`list connections failed: ${resp.status}`)
  const data = await resp.json()
  return (data.items ?? []) as PlaidConnection[]
}

export async function listBankAccounts(personId: string): Promise<BankAccount[]> {
  const resp = await fetch(
    `/api/v1/facts/current?personId=${personId}&entityType=bank_account&limit=200`
  )
  if (!resp.ok) throw new Error(`list accounts failed: ${resp.status}`)
  const data = await resp.json()
  return (data.items ?? []) as BankAccount[]
}

export async function disconnectPlaidAccount(
  entityInstanceId: string,
  schemaId: string,
  personId: string,
): Promise<void> {
  const docResp = await fetch('/api/v1/documents', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      personId,
      contentText: 'Disconnected Plaid account',
      sourceTypeId: await getUserInputSourceTypeId(),
      embedding: [],
      files: [],
      supersedesIds: [],
    }),
  })
  if (!docResp.ok) throw new Error(`create doc failed: ${docResp.status}`)
  const doc = await docResp.json()

  const factResp = await fetch('/api/v1/facts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      documentId: doc.id,
      schemaId,
      entityInstanceId,
      operationType: 'delete',
      fields: {},
      embedding: [],
    }),
  })
  if (!factResp.ok) throw new Error(`delete fact failed: ${factResp.status}`)
}

// ── Source Connections API ───────────────────────────────────────────────────

export async function listSourceConnections(personId: string): Promise<SourceConnection[]> {
  const resp = await fetch(`/api/v1/source-connections?personId=${personId}&limit=100`)
  if (!resp.ok) throw new Error(`list connections failed: ${resp.status}`)
  return ((await resp.json()).items ?? []) as SourceConnection[]
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

// ── Reference Data ───────────────────────────────────────────────────────────

let _userInputSourceTypeId: string | null = null
async function getUserInputSourceTypeId(): Promise<string> {
  if (_userInputSourceTypeId) return _userInputSourceTypeId
  const resp = await fetch('/api/v1/reference/source-types')
  if (!resp.ok) throw new Error('cannot fetch source types')
  const data = await resp.json()
  const match = (data.items ?? []).find((st: { name: string; id: string }) => st.name === 'user_input')
  if (!match) throw new Error('user_input source type not found')
  _userInputSourceTypeId = match.id as string
  return _userInputSourceTypeId!
}
