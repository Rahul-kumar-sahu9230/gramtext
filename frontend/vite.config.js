import basicSsl from '@vitejs/plugin-basic-ssl'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  // HTTPS (self-signed) because phone browsers only allow the live camera on secure pages.
  plugins: [react(), basicSsl()],
  server: {
    host: true,
    port: 5173,
    // The page calls /api on its own origin; Vite forwards it to the FastAPI backend.
    // This avoids mixed-content (https page -> http API) and CORS problems on phones.
    proxy: {
      '/api': 'http://127.0.0.1:8000',
    },
  },
})
