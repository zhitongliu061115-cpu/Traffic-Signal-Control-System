import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueJsx from '@vitejs/plugin-vue-jsx'
import vueDevTools from 'vite-plugin-vue-devtools'

const enableVueDevTools = process.env.VUE_DEVTOOLS === 'true'

// https://vite.dev/config/
export default defineConfig({
  envDir: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [
    vue(),
    vueJsx(),
    ...(enableVueDevTools ? [vueDevTools()] : []),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
