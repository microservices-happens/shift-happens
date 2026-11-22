import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests against a running Compose stack (the full system, not a mock).
 *
 *   docker compose -f docker-compose.microservices.yml --profile app up -d
 *   npm --prefix frontend run e2e
 *
 * The stack uses Caddy's self-signed certificate, so TLS verification is off here. One worker only:
 * the tests share the demo data and deliberately walk one business flow after another. The browser is
 * the locally installed Chrome by default; set E2E_BROWSER_CHANNEL=chromium (after
 * `npx playwright install chromium`) or any Playwright channel to change it.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://localhost:8443',
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...(process.env.E2E_BROWSER_CHANNEL
      ? { channel: process.env.E2E_BROWSER_CHANNEL }
      : { channel: 'chrome' }),
  },
});
