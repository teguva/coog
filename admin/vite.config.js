import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [svelte()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8090', timeout: 4 * 60 * 60 * 1000 },
      '/health': 'http://127.0.0.1:8090',
      '/ws': { target: 'http://127.0.0.1:8090', ws: true },
    },
  },
});
