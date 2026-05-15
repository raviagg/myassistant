import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const chatbotPort = process.env.CHATBOT_PORT ?? '8000'
const httpPort    = process.env.HTTP_SERVER_PORT ?? '8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: parseInt(process.env.FRONTEND_PORT ?? '5173'),
    proxy: {
      // Scala HTTP server — all REST API v1 routes (facts, schemas, plaid, etc.)
      '/api/v1': {
        target: `http://localhost:${httpPort}`,
        changeOrigin: true,
      },
      // Chatbot server — login, chat streaming, file upload
      '/api': {
        target: `http://localhost:${chatbotPort}`,
        changeOrigin: true,
      },
    },
  },
})
