import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const chatbotPort = process.env.CHATBOT_PORT ?? '8000'

export default defineConfig({
  plugins: [react()],
  server: {
    port: parseInt(process.env.FRONTEND_PORT ?? '5173'),
    proxy: {
      '/api': {
        target: `http://localhost:${chatbotPort}`,
        changeOrigin: true,
      },
    },
  },
})
