<script lang="ts">
  import { onMount } from 'svelte'

  type Message = {
    id: string
    receivedAt: string
    account: string
    from: string
    to: string
    text: string | null
    encoding: string
    parts: number
    part: number | null
    status: string
  }

  let messages: Message[] = $state([])
  let total = $state(0)
  let live = $state(false)
  let error: string | null = $state(null)

  async function load() {
    try {
      const res = await fetch('/api/v1/messages?limit=200')
      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
      const page = await res.json()
      messages = page.messages
      total = page.total
      error = null
    } catch (e) {
      error = `Could not load inbox: ${(e as Error).message}`
    }
  }

  async function clear() {
    await fetch('/api/v1/messages', { method: 'DELETE' })
    messages = []
    total = 0
  }

  onMount(() => {
    load()
    const stream = new EventSource('/api/v1/messages/stream')
    stream.onopen = () => (live = true)
    stream.onerror = () => (live = false)
    stream.addEventListener('message', (e) => {
      const m = JSON.parse(e.data) as Message
      messages = [m, ...messages].slice(0, 500)
      total += 1
    })
    stream.addEventListener('cleared', () => {
      messages = []
      total = 0
    })
    return () => stream.close()
  })

  function time(iso: string) {
    return new Date(iso).toLocaleTimeString([], { hour12: false })
  }
</script>

<header>
  <h1>Cellophane</h1>
  <span class="tagline">the see-through SMSC</span>
  <span class="spacer"></span>
  <span class="status" class:live>{live ? 'live' : 'reconnecting'}</span>
  <span class="count">{total} message{total === 1 ? '' : 's'}</span>
  <button onclick={clear} disabled={total === 0}>Clear inbox</button>
</header>

<main>
  {#if error}
    <p class="error">{error}</p>
  {/if}

  {#if messages.length === 0}
    <section class="empty">
      <p>No messages yet. Bind an SMPP client to port <code>2775</code>, or try:</p>
      <pre>curl -X POST localhost:8025/api/v1/send \
  -H 'content-type: application/json' \
  -d '&#123;"from":"MyApp","to":"8801711111111","text":"Your OTP is 482913"&#125;'</pre>
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
          <th>Encoding</th>
        </tr>
      </thead>
      <tbody>
        {#each messages as m (m.id)}
          <tr>
            <td class="time mono">{time(m.receivedAt)}</td>
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
                <span class="badge parts">{m.part}/{m.parts}</span>
              {/if}
            </td>
            <td class="muted">{m.encoding}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</main>
