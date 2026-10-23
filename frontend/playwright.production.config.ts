import { defineConfig } from "@playwright/test";

// 对已启动的同源构建产物执行回归，不启动 Vite 开发服务器。
export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: [
    "**/account.spec.ts",
    "**/workbench.spec.ts",
    "**/password-confirm.spec.ts",
  ],
  workers: 1,
  timeout: 60000,
  retries: 0,
  outputDir: "test-results/production",
  use: {
    baseURL: "http://127.0.0.1:8080",
    headless: true,
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
  },
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: "playwright-report/production" }],
  ],
});
