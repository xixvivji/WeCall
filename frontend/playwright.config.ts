import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 90000,
  use: {
    baseURL: "http://127.0.0.1:5173",
    headless: true,
    actionTimeout: 10000,
    navigationTimeout: 15000,
    trace: "off",
    screenshot: "off",
  },
  reporter: "list",
});
