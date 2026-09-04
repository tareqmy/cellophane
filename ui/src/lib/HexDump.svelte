<script lang="ts">
  import type { FieldView } from './api'

  let {
    hex,
    fields,
    active = null,
    onhover,
  }: { hex: string; fields: FieldView[]; active?: number | null; onhover: (i: number | null) => void } = $props()

  const bytes = $derived(hex.match(/../g) ?? [])
  const owner = $derived.by(() => {
    const map = new Array<number | null>(bytes.length).fill(null)
    fields.forEach((f, i) => {
      for (let b = f.offset; b < f.offset + f.length && b < map.length; b++) map[b] = i
    })
    return map
  })
  const rows = $derived.by(() => {
    const out: number[][] = []
    for (let i = 0; i < bytes.length; i += 16) out.push(bytes.slice(i, i + 16).map((_, j) => i + j))
    return out
  })

  function ascii(b: string): string {
    const code = parseInt(b, 16)
    return code >= 0x20 && code < 0x7f ? String.fromCharCode(code) : '.'
  }
</script>

<div class="hexdump" role="presentation" onmouseleave={() => onhover(null)}>
  {#each rows as row}
    <div class="row">
      <span class="offset">{row[0].toString(16).padStart(4, '0')}</span>
      <span class="bytes">
        {#each row as i}
          <span
            class="b"
            class:hl={owner[i] !== null && owner[i] === active}
            class:even={(owner[i] ?? 0) % 2 === 0}
            role="presentation"
            onmouseenter={() => onhover(owner[i])}>{bytes[i]}</span
          >
        {/each}
      </span>
      <span class="ascii">
        {#each row as i}
          <span class="b" class:hl={owner[i] !== null && owner[i] === active}>{ascii(bytes[i])}</span>
        {/each}
      </span>
    </div>
  {/each}
</div>

<style>
  .hexdump {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 1.6;
    overflow-x: auto;
    padding: 0.5rem 0.75rem;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 8px;
  }
  .row {
    display: flex;
    gap: 1rem;
    white-space: nowrap;
  }
  .offset {
    color: var(--muted);
  }
  .bytes .b {
    display: inline-block;
    padding: 0 0.2rem;
    border-radius: 3px;
  }
  .bytes .b.even {
    color: var(--fg);
  }
  .bytes .b:not(.even) {
    color: var(--muted);
  }
  .ascii {
    color: var(--muted);
  }
  .b.hl {
    background: var(--accent);
    color: #fff !important;
  }
</style>
