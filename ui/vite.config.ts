import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte()],
  build: {
    // Inside Gradle's build dir so `./gradlew clean` removes it and :server can consume it.
    outDir: 'build/dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8025',
    },
  },
})
