import { defineConfig } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] }),
    tailwindcss(),
  ],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 3000,
    // Backend istekleri proxy'den geçiyor: tarayıcı her şeyi tek origin
    // (localhost:3000) olarak görür, böylece geliştirmede CORS ve preflight
    // tamamen devre dışı kalır. Üretimde nginx aynı işi yapar.
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
      '/docs': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
})
