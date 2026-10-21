import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  workers: 1,
  timeout: 45000,
  retries: 0,
  use: {
    baseURL: "http://127.0.0.1:5173",
    headless: true,
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
  },
  reporter: [["list"], ["html", { open: "never" }]],
  webServer: [
    {
      command:
        "pwsh -NoProfile -File ../deploy/Start-Web.ps1 -DataDirectory ./target/e2e/ai -DatabaseSchema wb_e2e_default -SessionNamespace pdf-workbench:e2e:default",
      cwd: "../backend",
      url: "http://127.0.0.1:8080/v3/api-docs",
      reuseExistingServer: true,
      timeout: 120000,
    },
    {
      command: "npm run dev",
      url: "http://127.0.0.1:5173",
      reuseExistingServer: true,
      timeout: 60000,
    },
  ],
});
