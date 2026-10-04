import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: { output: { manualChunks: { pdfjs: ["pdfjs-dist"] } } },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/v3": "http://127.0.0.1:8080",
      "/swagger-ui": "http://127.0.0.1:8080",
    },
  },
  test: { environment: "jsdom", include: ["tests/**/*.test.ts"] },
});
