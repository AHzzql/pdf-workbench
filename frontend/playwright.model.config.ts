import { defineConfig } from "@playwright/test";
import gateway from "./playwright.gateway.config";
export default defineConfig({
  ...gateway,
  testMatch: "**/model.live.ts",
  timeout: 120000,
});
