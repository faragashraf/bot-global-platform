import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const hubTarget = loadEnv(mode, '.', '').VITE_HUB_TARGET ?? 'http://localhost:5173'
  return {
    plugins: [react()],
    server: {
      port: 4173,
      strictPort: true,
      proxy: {
        '/api': hubTarget,
        '/hubs': {
          target: hubTarget,
          ws: true,
        },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  }
})
