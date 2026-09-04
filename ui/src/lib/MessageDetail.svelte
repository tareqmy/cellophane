<script lang="ts">
  import { formatDateTime, statusClass, type MessageDetail, type SegmentView } from './api'
  import HexDump from './HexDump.svelte'

  let { detail }: { detail: MessageDetail } = $props()

  let active: Record<string, number | null> = $state({})
  let openPdu: Record<string, boolean> = $state({})

  function hover(seg: SegmentView, i: number | null) {
    active = { ...active, [seg.messageId]: i }
  }

  function toggle(seg: SegmentView) {
    openPdu = { ...openPdu, [seg.messageId]: !(openPdu[seg.messageId] ?? detail.segments.length === 1) }
  }

  function isOpen(seg: SegmentView) {
    return openPdu[seg.messageId] ?? detail.segments.length === 1
  }

  const timeline = $derived(
    detail.segments
      .flatMap((s) => s.events.map((e) => ({ ...e, part: s.sequence })))
      .sort((a, b) => a.at.localeCompare(b.at)),
  )

  function clock(iso: string) {
    const d = new Date(iso)
    return `${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, '0')}`
  }

  function label(type: string) {
    return type.replace('DLR_', 'receipt ').toLowerCase()
  }
</script>

<article class="detail">
  <div class="meta">
    <div class="route">
      <span class="mono addr">{detail.from.address}</span>
      <span class="arrow">→</span>
      <span class="mono addr">{detail.to.address}</span>
    </div>
    <dl>
      <dt>Received</dt>
      <dd>{formatDateTime(detail.receivedAt)}</dd>
      <dt>Account</dt>
      <dd><span class="badge">{detail.account}</span></dd>
      <dt>Encoding</dt>
      <dd>{detail.encoding} <span class="muted">(data_coding 0x{detail.dataCoding.toString(16).padStart(2, '0')})</span></dd>
      <dt>Status</dt>
      <dd><span class="badge status {statusClass(detail.status)}">{detail.status}</span></dd>
      {#if detail.parts > 1}
        <dt>Parts</dt>
        <dd>
          {detail.partsReceived} of {detail.parts} received
          <span class="muted">(concat reference {detail.concatReference})</span>
        </dd>
      {/if}
      <dt>Id</dt>
      <dd class="mono">{detail.id}</dd>
    </dl>
  </div>

  <section>
    <h3>Text</h3>
    {#if detail.text === null}
      <p class="muted"><em>binary payload, see the hex dump</em></p>
    {:else if detail.text === ''}
      <p class="muted"><em>empty</em></p>
    {:else}
      <p class="text">{detail.text}</p>
    {/if}
  </section>

  <section>
    <h3>Timeline</h3>
    <ol class="timeline">
      {#each timeline as e}
        <li class={e.type.toLowerCase()}>
          <span class="mono muted when">{clock(e.at)}</span>
          <span class="what">{label(e.type)}</span>
          {#if detail.parts > 1}
            <span class="badge">part {e.part}</span>
          {/if}
          <span class="muted">{e.detail}</span>
        </li>
      {/each}
    </ol>
  </section>

  {#each detail.segments as seg (seg.messageId)}
    <section class="segment">
      <h3>
        {#if detail.parts > 1}
          Part {seg.sequence} of {detail.parts}
        {:else}
          PDU
        {/if}
        <span class="badge status {statusClass(seg.status)}">{seg.status}</span>
        <span class="muted small">
          submit_sm seq {seg.pdu.sequenceNumber} · session {seg.sessionId} · message_id
          <span class="mono">{seg.messageId}</span>
        </span>
        <button class="link" onclick={() => toggle(seg)}>{isOpen(seg) ? 'hide' : 'show'}</button>
      </h3>

      {#if isOpen(seg)}
        {#if seg.udh}
          <p class="small">
            UDH:
            {#if seg.udh.total}
              concatenated, reference {seg.udh.reference}, part {seg.udh.sequence}/{seg.udh.total}
            {/if}
            {#each seg.udh.elements as el}
              <span class="badge">IE 0x{el.id.toString(16).padStart(2, '0')} {el.hex}</span>
            {/each}
          </p>
        {/if}

        <div class="pdu">
          <table class="fields">
            <tbody>
              {#each seg.fields as f, i}
                <tr
                  class:hl={active[seg.messageId] === i}
                  class:warn={f.name === 'truncated' || f.name === 'trailing bytes'}
                  onmouseenter={() => hover(seg, i)}
                  onmouseleave={() => hover(seg, null)}
                >
                  <td class="mono muted off">{f.offset.toString(16).padStart(4, '0')}</td>
                  <td class="name">{f.name}</td>
                  <td class="value">{f.value}</td>
                </tr>
              {/each}
            </tbody>
          </table>
          <HexDump
            hex={seg.rawPduHex}
            fields={seg.fields}
            active={active[seg.messageId] ?? null}
            onhover={(i) => hover(seg, i)}
          />
        </div>
      {/if}
    </section>
  {/each}
</article>

<style>
  .detail {
    display: flex;
    flex-direction: column;
    gap: 1.25rem;
  }
  .route {
    font-size: 1.1rem;
    margin-bottom: 0.75rem;
  }
  .arrow {
    color: var(--muted);
    margin: 0 0.5rem;
  }
  dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 0.25rem 1rem;
    margin: 0;
  }
  dt {
    color: var(--muted);
  }
  dd {
    margin: 0;
  }
  h3 {
    margin: 0 0 0.5rem;
    font-size: 0.85rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--muted);
    display: flex;
    align-items: baseline;
    gap: 0.75rem;
    flex-wrap: wrap;
  }
  h3 .small {
    text-transform: none;
    letter-spacing: 0;
    font-weight: normal;
  }
  .timeline {
    list-style: none;
    margin: 0;
    padding: 0.25rem 0.75rem;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 8px;
    font-size: 12.5px;
  }
  .timeline li {
    display: flex;
    gap: 0.75rem;
    align-items: baseline;
    padding: 0.3rem 0;
    border-bottom: 1px solid var(--line);
  }
  .timeline li:last-child {
    border-bottom: none;
  }
  .timeline .what {
    white-space: nowrap;
    font-weight: 500;
  }
  .timeline li.rejected .what,
  .timeline li.dlr_queued .what {
    color: var(--warn);
  }
  .timeline li.dlr_sent .what,
  .timeline li.dlr_acked .what,
  .timeline li.dlr_simulated .what {
    color: var(--ok);
  }
  .text {
    white-space: pre-wrap;
    word-break: break-word;
    padding: 0.75rem 1rem;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 8px;
    margin: 0;
    font-size: 1rem;
  }
  .pdu {
    display: grid;
    gap: 0.75rem;
  }
  .fields {
    width: 100%;
    border-collapse: collapse;
    font-size: 12.5px;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 8px;
  }
  .fields td {
    padding: 0.25rem 0.6rem;
    border-bottom: 1px solid var(--line);
    vertical-align: top;
  }
  .fields tr:last-child td {
    border-bottom: none;
  }
  .fields tr.hl td {
    background: color-mix(in srgb, var(--accent) 18%, transparent);
  }
  .fields tr.warn td.name {
    color: var(--warn);
  }
  .off {
    width: 3rem;
  }
  .name {
    white-space: nowrap;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  .value {
    word-break: break-all;
  }
  .small {
    font-size: 12.5px;
  }
  button.link {
    background: none;
    border: none;
    color: var(--accent);
    cursor: pointer;
    padding: 0;
    font-size: 0.8rem;
  }
</style>
