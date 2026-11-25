import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // Relative URLs. An absolute /assets path 404s when the WebView is not served
  // from the host root, and the page stays black.
  base: './',
  server: {
    port: 5173,
  },
});
