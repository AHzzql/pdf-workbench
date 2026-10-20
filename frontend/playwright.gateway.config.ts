import { defineConfig } from "@playwright/test";
import production from "./playwright.production.config";

export default defineConfig({
  ...production,
  outputDir: "test-results/gateway",
  use: { ...production.use, baseURL: "http://127.0.0.1:5173" },
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: "playwright-report/gateway" }],
  ],
});
