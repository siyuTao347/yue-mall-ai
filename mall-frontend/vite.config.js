import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/api/agent/realtime/ws': {
        target: process.env.VITE_GATEWAY_URL || 'http://localhost:8080',
        changeOrigin: true,
        ws: true
      },
      '/api': {
        target: process.env.VITE_GATEWAY_URL || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
