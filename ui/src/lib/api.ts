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

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return res.json() as Promise<T>
}

export async function listMessages(q: string, limit = 200): Promise<MessagesPage> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (q) params.set('q', q)
  return json(await fetch(`/api/v1/messages?${params}`))
}

export async function getMessage(id: string): Promise<MessageDetail> {
  return json(await fetch(`/api/v1/messages/${encodeURIComponent(id)}`))
}

export async function clearMessages(): Promise<void> {
  const res = await fetch('/api/v1/messages', { method: 'DELETE' })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
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
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body.detail) detail = body.detail
    } catch {
      /* not json */
    }
    throw new Error(detail)
  }
  return res.json()
}

export function statusClass(status: string): string {
  if (status === 'DELIVRD') return 'ok'
  if (status === 'ACCEPTED' || status === 'ACCEPTD' || status === 'ENROUTE' || status === 'UNKNOWN') return 'pending'
  return 'bad'
}

export function matches(m: MessageSummary, q: string): boolean {
  if (!q) return true
  const needle = q.toLowerCase()
  return [m.from, m.to, m.text ?? ''].some((s) => s.toLowerCase().includes(needle))
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour12: false })
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, '0')}`
}
