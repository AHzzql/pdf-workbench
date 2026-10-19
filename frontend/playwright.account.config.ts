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
      command: "mvn spring-boot:run",
      cwd: "../backend",
      url: "http://127.0.0.1:8082/v3/api-docs",
      reuseExistingServer: false,
      timeout: 120000,
      env: {
        SERVER_PORT: "8082",
        ACCOUNT_DATA_DIRECTORY: path.resolve(
          "../backend/target/account-e2e/accounts",
        ),
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
