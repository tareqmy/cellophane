export type MessageSummary = {
  id: string
  receivedAt: string
  updatedAt: string
  account: string
  from: string
  to: string
  text: string | null
  encoding: string
  parts: number
  partsReceived: number
  status: string
}

export type AddressView = { ton: number; npi: number; address: string }
export type TlvView = { tag: number; name: string; length: number; hex: string }
export type PduView = {
  sequenceNumber: number
  serviceType: string
  esmClass: number
  protocolId: number
  priorityFlag: number
  scheduleDeliveryTime: string
  validityPeriod: string
  registeredDelivery: number
  replaceIfPresent: number
  dataCoding: number
  smDefaultMsgId: number
  shortMessageHex: string
  tlvs: TlvView[]
}
export type UdhView = {
  reference: number | null
  total: number | null
  sequence: number | null
  elements: { id: number; hex: string }[]
}
export type FieldView = { offset: number; length: number; name: string; value: string }
export type EventView = { at: string; type: string; detail: string }
export type SegmentView = {
  messageId: string
  sequence: number
  receivedAt: string
  sessionId: string
  text: string | null
  status: string
  events: EventView[]
  pdu: PduView
  udh: UdhView | null
  rawPduHex: string
  fields: FieldView[]
}
export type MessageDetail = {
  id: string
  receivedAt: string
  updatedAt: string
  account: string
  from: AddressView
  to: AddressView
  text: string | null
  encoding: string
  dataCoding: number
  parts: number
  partsReceived: number
  concatReference: number | null
  status: string
  segments: SegmentView[]
}

export type MessagesPage = { total: number; messages: MessageSummary[] }

export type BindType = 'TRANSMITTER' | 'RECEIVER' | 'TRANSCEIVER'
export type SessionInfo = {
  id: string
  remoteAddress: string
  account: string | null
  bindType: BindType | null
  connectedAt: string
  boundAt: string | null
  submitted: number
  inFlight: number
  window: number | null
}
export type Stats = {
  smppPort: number
  messages: number
  capacity: number
  byStatus: Record<string, number>
  sessions: number
  boundSessions: number
  streamSubscribers: number
  accounts: string[]
  totals: {
    submitted: number
    accepted: number
    rejected: number
    receiptsSent: number
    moSent: number
    tps: number
  }
}
export type MoSent = {
  account: string
  sessionId: string
  parts: number
  sequenceNumbers: number[]
  dataCoding: number
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return res.json() as Promise<T>
}

/** Status filter presets offered in the UI, as the API's comma-separated `status` values. */
export const STATUS_FILTERS: { label: string; statuses: string[] }[] = [
  { label: 'All statuses', statuses: [] },
  { label: 'Accepted, no receipt yet', statuses: ['ACCEPTED'] },
  { label: 'Delivered', statuses: ['DELIVRD'] },
  { label: 'Failed delivery', statuses: ['UNDELIV', 'EXPIRED', 'REJECTD', 'DELETED', 'UNKNOWN'] },
  { label: 'Rejected submit', statuses: ['REJECTED'] },
]

export async function listMessages(q: string, statuses: string[] = [], limit = 200): Promise<MessagesPage> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (q) params.set('q', q)
  if (statuses.length) params.set('status', statuses.join(','))
  return json(await fetch(`/api/v1/messages?${params}`))
}

export async function getMessage(id: string): Promise<MessageDetail> {
  return json(await fetch(`/api/v1/messages/${encodeURIComponent(id)}`))
}

export async function clearMessages(): Promise<void> {
  const res = await fetch('/api/v1/messages', { method: 'DELETE' })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
}

export async function listSessions(): Promise<SessionInfo[]> {
  return json(await fetch('/api/v1/sessions'))
}

export async function getStats(): Promise<Stats> {
  return json(await fetch('/api/v1/stats'))
}

/** Injects a mobile-originated message toward a bound receiver of the account (any account when empty). */
export async function sendMo(mo: { account: string; from: string; to: string; text: string }): Promise<MoSent> {
  const res = await fetch('/api/v1/mo', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(mo),
  })
  if (!res.ok) throw new Error(await problemDetail(res))
  return res.json()
}

async function problemDetail(res: Response): Promise<string> {
  let detail = `${res.status} ${res.statusText}`
  try {
    const body = await res.json()
    if (body.detail) detail = body.detail
  } catch {
    /* not json */
  }
  return detail
}

export async function getRules(): Promise<string> {
  const res = await fetch('/api/v1/rules')
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return res.text()
}

export async function putRules(yaml: string): Promise<{ rules: number }> {
  const res = await fetch('/api/v1/rules', {
    method: 'PUT',
    headers: { 'content-type': 'application/yaml' },
    body: yaml,
  })
  if (!res.ok) throw new Error(await problemDetail(res))
  return res.json()
}

export function statusClass(status: string): string {
  if (status === 'DELIVRD') return 'ok'
  if (status === 'ACCEPTED' || status === 'ACCEPTD' || status === 'ENROUTE' || status === 'UNKNOWN') return 'pending'
  return 'bad'
}

export function matches(m: MessageSummary, q: string, statuses: string[] = []): boolean {
  if (statuses.length && !statuses.includes(m.status)) return false
  if (!q) return true
  const needle = q.toLowerCase()
  return [m.from, m.to, m.text ?? ''].some((s) => s.toLowerCase().includes(needle))
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour12: false })
}

/** "12s", "3m 04s", "1h 02m": how long ago an instant was. */
export function since(iso: string, now = Date.now()): string {
  const s = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s`
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, '0')}`
}
