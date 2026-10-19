import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
const backendTarget =
  process.env.PDF_WORKBENCH_BACKEND_URL || "http://127.0.0.1:8080";
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: { output: { manualChunks: { pdfjs: ["pdfjs-dist"] } } },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": backendTarget,
      "/v3": backendTarget,
      "/swagger-ui": backendTarget,
    },
  },
  test: { environment: "jsdom", include: ["tests/**/*.test.ts"] },
});
