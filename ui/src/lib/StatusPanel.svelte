<script lang="ts">
  import { onMount } from 'svelte'
  import { getStats, listSessions, sendMo, since, type SessionInfo, type Stats } from './api'

  let { onclose }: { onclose: () => void } = $props()

  let stats: Stats | null = $state(null)
  let sessions: SessionInfo[] = $state([])
  let error: string | null = $state(null)
  let now = $state(Date.now())
  let mo = $state({ account: '', from: '8801711111111', to: 'MyApp', text: 'STOP' })
  let moMessage: { ok: boolean; text: string } | null = $state(null)
  let moBusy = $state(false)

  /** Accounts that could receive an MO right now: one with a receiver or transceiver bound. */
  let receivers = $derived(
    [...new Set(sessions.filter((s) => s.bindType && s.bindType !== 'TRANSMITTER').map((s) => s.account!))].sort(),
  )

  async function refresh() {
    try {
      ;[stats, sessions] = await Promise.all([getStats(), listSessions()])
      now = Date.now()
      error = null
    } catch (e) {
      error = `Could not load status: ${(e as Error).message}`
    }
  }

  async function inject() {
    moBusy = true
    moMessage = null
    try {
      const sent = await sendMo(mo)
      moMessage = {
        ok: true,
        text: `deliver_sm sent to ${sent.sessionId} (${sent.account}), ${sent.parts} part${sent.parts === 1 ? '' : 's'}, seq ${sent.sequenceNumbers.join(', ')}`,
      }
      refresh()
    } catch (e) {
      moMessage = { ok: false, text: (e as Error).message }
    } finally {
      moBusy = false
    }
  }

  onMount(() => {
    refresh()
    const timer = setInterval(refresh, 2000)
    return () => clearInterval(timer)
  })
</script>

<section class="status-panel">
  <div class="bar">
    <strong>Sessions &amp; stats</strong>
    <span class="muted">refreshes every two seconds</span>
    <span class="spacer"></span>
    <button class="close" onclick={onclose} title="Close">✕</button>
  </div>

  {#if error}
    <p class="bad">{error}</p>
  {:else if stats}
    <div class="tiles">
      <div class="tile">
        <span class="label">Inbox</span>
        <span class="value">{stats.messages}<span class="of"> / {stats.capacity}</span></span>
      </div>
      <div class="tile">
        <span class="label">Submits</span>
        <span class="value">{stats.totals.submitted}</span>
        <span class="sub"><span class="ok">{stats.totals.accepted} ok</span> · <span class="bad">{stats.totals.rejected} rejected</span></span>
      </div>
      <div class="tile">
        <span class="label">Rate</span>
        <span class="value">{stats.totals.tps.toFixed(1)}<span class="of"> /s</span></span>
        <span class="sub">last 10s</span>
      </div>
      <div class="tile">
        <span class="label">Receipts</span>
        <span class="value">{stats.totals.receiptsSent}</span>
        <span class="sub">deliver_sm sent</span>
      </div>
      <div class="tile">
        <span class="label">MO</span>
        <span class="value">{stats.totals.moSent}</span>
        <span class="sub">injected</span>
      </div>
      <div class="tile">
        <span class="label">Sessions</span>
        <span class="value">{stats.boundSessions}<span class="of"> / {stats.sessions}</span></span>
        <span class="sub">bound / connected</span>
      </div>
      <div class="tile">
        <span class="label">SMPP</span>
        <span class="value">:{stats.smppPort}</span>
        <span class="sub">{stats.accounts.join(', ')}</span>
      </div>
    </div>

    {#if sessions.length === 0}
      <p class="muted">No ESME connected. Bind to port <code>{stats.smppPort}</code> and it appears here.</p>
    {:else}
      <table>
        <thead>
          <tr>
            <th>Session</th>
            <th>Remote</th>
            <th>Account</th>
            <th>Bind</th>
            <th class="num">In flight</th>
            <th class="num">Submits</th>
            <th class="num">Connected</th>
            <th class="num">Bound</th>
          </tr>
        </thead>
        <tbody>
          {#each sessions as s (s.id)}
            <tr>
              <td class="mono">{s.id}</td>
              <td class="mono">{s.remoteAddress}</td>
              <td>{#if s.account}<span class="badge">{s.account}</span>{:else}<span class="muted">—</span>{/if}</td>
              <td>
                {#if s.bindType}
                  <span class="badge bind">{s.bindType.toLowerCase()}</span>
                {:else}
                  <span class="muted">not bound</span>
                {/if}
              </td>
              <td class="num mono" class:full={s.window !== null && s.inFlight >= s.window}>
                {s.inFlight}{#if s.window !== null}<span class="of">&nbsp;/ {s.window}</span>{/if}
              </td>
              <td class="num mono">{s.submitted}</td>
              <td class="num mono">{since(s.connectedAt, now)}</td>
              <td class="num mono">{s.boundAt ? since(s.boundAt, now) : '—'}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}

    <form class="mo" onsubmit={(e) => { e.preventDefault(); inject() }}>
      <span class="label">Inject MO</span>
      <select aria-label="Account" bind:value={mo.account} disabled={receivers.length === 0}>
        <option value="">{receivers.length ? 'any account' : 'no receiver bound'}</option>
        {#each receivers as a}
          <option value={a}>{a}</option>
        {/each}
      </select>
      <input aria-label="From" bind:value={mo.from} placeholder="from (subscriber)" />
      <input aria-label="To" bind:value={mo.to} placeholder="to (short code)" />
      <input aria-label="Text" class="text" bind:value={mo.text} placeholder="text" />
      <button type="submit" disabled={moBusy || receivers.length === 0 || !mo.from || !mo.to || !mo.text}>Send deliver_sm</button>
      {#if moMessage}
        <span class:ok={moMessage.ok} class:bad={!moMessage.ok}>{moMessage.text}</span>
      {/if}
    </form>
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</section>

<style>
  .status-panel {
    border-bottom: 1px solid var(--line);
    background: var(--panel);
    padding: 0.6rem 1.25rem 0.75rem;
    display: flex;
    flex-direction: column;
    gap: 0.6rem;
  }
  .bar {
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }
  .tiles {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
    gap: 0.5rem;
  }
  .tile {
    display: flex;
    flex-direction: column;
    gap: 0.1rem;
    padding: 0.5rem 0.7rem;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--bg);
    min-width: 0;
  }
  .label {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted);
  }
  .value {
    font-size: 20px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    line-height: 1.2;
  }
  .of {
    font-size: 13px;
    font-weight: 400;
    color: var(--muted);
  }
  .sub {
    font-size: 12px;
    color: var(--muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12.5px;
  }
  th,
  td {
    text-align: left;
    padding: 0.3rem 0.6rem;
    border-bottom: 1px solid var(--line);
    white-space: nowrap;
  }
  th {
    font-weight: 500;
    color: var(--muted);
  }
  .num {
    text-align: right;
  }
  .full {
    color: var(--warn);
    font-weight: 600;
  }
  .badge.bind {
    border-color: var(--accent);
    color: var(--accent);
  }
  .mo {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
    font-size: 12.5px;
  }
  .mo input,
  .mo select {
    font: inherit;
    padding: 0.3rem 0.5rem;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: var(--bg);
    color: var(--fg);
    min-width: 8rem;
  }
  .mo input.text {
    flex: 1;
    min-width: 12rem;
  }
  p {
    margin: 0;
    font-size: 12.5px;
  }
  .ok {
    color: var(--ok);
  }
  .bad {
    color: #dc2626;
  }
  button.close {
    border: none;
    background: none;
    color: var(--muted);
  }
</style>
