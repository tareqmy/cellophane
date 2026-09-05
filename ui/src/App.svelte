<script lang="ts">
  import { onMount } from 'svelte'
  import {
    clearMessages,
    formatTime,
    getMessage,
    listMessages,
    matches,
    statusClass,
    type MessageDetail,
    type MessageSummary,
  } from './lib/api'
  import Detail from './lib/MessageDetail.svelte'
  import RulesPanel from './lib/RulesPanel.svelte'
  import { applyTheme, loadTheme, nextTheme, type Theme } from './lib/theme'

  let messages: MessageSummary[] = $state([])
  let total = $state(0)
  let live = $state(false)
  let error: string | null = $state(null)
  let query = $state('')
  let selectedId: string | null = $state(null)
  let detail: MessageDetail | null = $state(null)
  let showRules = $state(false)
  let theme: Theme = $state(loadTheme())

  function cycleTheme() {
    theme = nextTheme(theme)
    applyTheme(theme)
  }

  let searchTimer: ReturnType<typeof setTimeout> | undefined

  async function load() {
    try {
      const page = await listMessages(query)
      messages = page.messages
      total = page.total
      error = null
    } catch (e) {
      error = `Could not load inbox: ${(e as Error).message}`
    }
  }

  function search(value: string) {
    query = value
    clearTimeout(searchTimer)
    searchTimer = setTimeout(load, 150)
  }

  async function select(id: string) {
    selectedId = id
    if (location.hash !== `#${id}`) history.replaceState(null, '', `#${id}`)
    try {
      detail = await getMessage(id)
    } catch (e) {
      detail = null
      error = `Could not load message: ${(e as Error).message}`
    }
  }

  function close() {
    selectedId = null
    detail = null
    if (location.hash) history.replaceState(null, '', location.pathname + location.search)
  }

  async function clear() {
    await clearMessages()
    messages = []
    total = 0
    close()
  }

  function upsert(m: MessageSummary, isNew: boolean) {
    const i = messages.findIndex((x) => x.id === m.id)
    if (i >= 0) {
      messages[i] = m
    } else if (isNew && matches(m, query)) {
      messages = [m, ...messages].slice(0, 500)
      total += 1
    }
    if (selectedId === m.id) select(m.id)
  }

  onMount(() => {
    applyTheme(theme)
    load()
    const fromHash = location.hash.slice(1)
    if (fromHash) select(fromHash)
    const stream = new EventSource('/api/v1/messages/stream')
    stream.onopen = () => (live = true)
    stream.onerror = () => (live = false)
    stream.addEventListener('message', (e) => upsert(JSON.parse(e.data), true))
    stream.addEventListener('updated', (e) => upsert(JSON.parse(e.data), false))
    stream.addEventListener('cleared', () => {
      messages = []
      total = 0
      close()
    })
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      stream.close()
      window.removeEventListener('keydown', onKey)
    }
  })
</script>

<header>
  <h1>Cellophane</h1>
  <span class="tagline">the see-through SMSC</span>
  <input
    type="search"
    placeholder="Search sender, recipient or text"
    value={query}
    oninput={(e) => search((e.target as HTMLInputElement).value)}
  />
  <span class="spacer"></span>
  <span class="status" class:live>{live ? 'live' : 'reconnecting'}</span>
  <span class="count">{total} message{total === 1 ? '' : 's'}</span>
  <button onclick={() => (showRules = !showRules)} class:active={showRules}>Rules</button>
  <button onclick={cycleTheme} title="Theme: {theme} (click to change)" class="theme"
    >{theme === 'dark' ? '☾' : theme === 'light' ? '☀' : '◐'}</button
  >
  <button onclick={clear} disabled={total === 0}>Clear inbox</button>
</header>

{#if showRules}
  <RulesPanel onclose={() => (showRules = false)} />
{/if}

<div class="layout" class:split={selectedId !== null}>
  <main>
    {#if error}
      <p class="error">{error}</p>
    {/if}

    {#if messages.length === 0}
      <section class="empty">
        {#if query}
          <p>Nothing matches “{query}”.</p>
        {:else}
          <p>No messages yet. Bind an SMPP client to port <code>2775</code>, or try:</p>
          <pre>curl -X POST localhost:8025/api/v1/send \
  -H 'content-type: application/json' \
  -d '&#123;"from":"MyApp","to":"8801711111111","text":"Your OTP is 482913"&#125;'</pre>
        {/if}
      </section>
    {:else}
      <table>
        <thead>
          <tr>
            <th class="time">Time</th>
            <th>Account</th>
            <th>From</th>
            <th>To</th>
            <th class="text">Text</th>
            <th>Status</th>
            <th>Encoding</th>
          </tr>
        </thead>
        <tbody>
          {#each messages as m (m.id)}
            <tr class:selected={m.id === selectedId} onclick={() => select(m.id)}>
              <td class="time mono">{formatTime(m.receivedAt)}</td>
              <td><span class="badge">{m.account}</span></td>
              <td class="mono">{m.from}</td>
              <td class="mono">{m.to}</td>
              <td class="text">
                {#if m.text === null}
                  <em class="muted">binary</em>
                {:else}
                  {m.text}
                {/if}
                {#if m.parts > 1}
                  <span class="badge parts" class:incomplete={m.partsReceived < m.parts}
                    >{m.partsReceived}/{m.parts} parts</span
                  >
                {/if}
              </td>
              <td><span class="badge status {statusClass(m.status)}">{m.status}</span></td>
              <td class="muted">{m.encoding}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}
  </main>

  {#if selectedId !== null}
    <aside>
      <div class="aside-bar">
        <span class="muted">Message</span>
        <button class="close" onclick={close} title="Close (Esc)">✕</button>
      </div>
      {#if detail}
        <Detail {detail} />
      {:else}
        <p class="muted">Loading…</p>
      {/if}
    </aside>
  {/if}
</div>
