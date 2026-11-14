/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the API is proxied, so the browser sees a single origin and
// no CORS configuration is needed. In production Caddy does the same.
const apiTarget = process.env.FET_API_URL ?? 'http://127.0.0.1:8000'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: { '/api': { target: apiTarget, changeOrigin: false } },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: { '/api': { target: apiTarget, changeOrigin: false } },
  },
  // ECharts dominates the bundle; one cached download on a private LAN is acceptable.
  build: { sourcemap: false, chunkSizeWarningLimit: 1000 },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Same timezone the household uses, so date logic is tested as it runs in Dhaka.
    env: { TZ: 'Asia/Dhaka' },
  },
})
