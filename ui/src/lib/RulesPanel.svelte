<script lang="ts">
  import { onMount } from 'svelte'
  import { getRules, putRules } from './api'

  let { onclose }: { onclose: () => void } = $props()

  let yaml = $state('')
  let message: { ok: boolean; text: string } | null = $state(null)
  let busy = $state(false)

  onMount(async () => {
    try {
      yaml = await getRules()
    } catch (e) {
      message = { ok: false, text: `Could not load rules: ${(e as Error).message}` }
    }
  })

  async function apply() {
    busy = true
    message = null
    try {
      const r = await putRules(yaml)
      message = { ok: true, text: `Applied ${r.rules} rule${r.rules === 1 ? '' : 's'}.` }
    } catch (e) {
      message = { ok: false, text: (e as Error).message }
    } finally {
      busy = false
    }
  }
</script>

<section class="rules">
  <div class="bar">
    <strong>Operator rules</strong>
    <span class="muted">first match wins; edit and apply without restarting</span>
    <span class="spacer"></span>
    <button onclick={apply} disabled={busy}>Apply</button>
    <button class="close" onclick={onclose} title="Close">✕</button>
  </div>
  <textarea bind:value={yaml} spellcheck="false" rows="12"></textarea>
  {#if message}
    <p class:ok={message.ok} class:bad={!message.ok}>{message.text}</p>
  {/if}
</section>

<style>
  .rules {
    border-bottom: 1px solid var(--line);
    background: var(--panel);
    padding: 0.6rem 1.25rem 0.75rem;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .bar {
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }
  textarea {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12.5px;
    line-height: 1.5;
    padding: 0.6rem 0.75rem;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--bg);
    color: var(--fg);
    resize: vertical;
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
    white-space: pre-wrap;
  }
  button.close {
    border: none;
    background: none;
    color: var(--muted);
  }
</style>
