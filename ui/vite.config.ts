import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte()],
  build: {
    // Under Maven's target/ so `./mvnw clean` removes it; the ui module packages it as static/ resources.
    outDir: 'target/dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8025',
    },
  },
})
