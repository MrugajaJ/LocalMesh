import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target:    'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  define: {
    // Allow VITE_MOCK to be read at runtime
  },
})
