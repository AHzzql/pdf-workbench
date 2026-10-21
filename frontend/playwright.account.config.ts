import { defineConfig } from "@playwright/test";
import path from "node:path";
export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: ["**/account.spec.ts", "**/workbench.spec.ts"],
  workers: 1,
  timeout: 60000,
  retries: 0,
  use: {
    baseURL: "http://127.0.0.1:5182",
    headless: true,
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
  },
  reporter: [["list"], ["html", { open: "never" }]],
  webServer: [
    {
      command:
        "pwsh -NoProfile -File ../deploy/Start-Web.ps1 -Port 8082 -DataDirectory ./target/account-e2e/ai -DatabaseSchema wb_e2e_account -SessionNamespace pdf-workbench:e2e:account",
      cwd: "../backend",
      url: "http://127.0.0.1:8082/v3/api-docs",
      reuseExistingServer: false,
      timeout: 120000,
      env: {
        SERVER_PORT: "8082",
        SPRING_CONFIG_IMPORT: "optional:classpath:e2e-no-import.yml",
        AI_API_KEY: "",
        AI_BASE_URL: "http://127.0.0.1:9",
        AI_RERANK_URL: "http://127.0.0.1:9",
        AI_DATA_DIRECTORY: path.resolve("../backend/target/account-e2e/ai"),
      },
    },
    {
      command: "npm run dev -- --port 5182",
      url: "http://127.0.0.1:5182",
      reuseExistingServer: false,
      timeout: 60000,
      env: { PDF_WORKBENCH_BACKEND_URL: "http://127.0.0.1:8082" },
    },
  ],
});
