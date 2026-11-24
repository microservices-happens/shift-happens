#!/usr/bin/env node
/**
 * Small authenticated GraphQL load generator for the scheduling query role.
 *
 *   node scripts/load-schedule.mjs
 *   LOAD_REQUESTS=2000 LOAD_CONCURRENCY=20 node scripts/load-schedule.mjs
 *
 * Run it against the full Compose stack, optionally with two query replicas:
 *   docker compose -f docker-compose.microservices.yml --profile app up -d --scale scheduling-query=2
 *   node scripts/load-schedule.mjs
 *
 * The script prints throughput, error count and p50/p95/p99 latency. It is a development aid, not a
 * benchmark: the service, the gateway and this client share one host.
 */
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const BASE = (process.env.E2E_BASE_URL ?? 'https://localhost:8443').replace(/\/$/, '');
const EMAIL = process.env.E2E_EMAIL ?? 'sofie.jensen@shift-happens.example';
const PASSWORD = process.env.E2E_PASSWORD ?? 'Demo1234!';
const REQUESTS = Number(process.env.LOAD_REQUESTS ?? 500);
const CONCURRENCY = Number(process.env.LOAD_CONCURRENCY ?? 10);

const QUERY = `query LoadShifts($from: DateTime!, $to: DateTime!) {
  shifts(from: $from, to: $to) {
    shiftId
    shiftName
    status
    assignments { shiftAssignmentId employee { employeeId firstName lastName } }
  }
}`;

async function login() {
  const response = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
  });
  if (!response.ok) throw new Error(`login failed: HTTP ${response.status}`);
  return (await response.json()).token;
}

function window() {
  const from = new Date(Date.now() - 7 * 86_400_000).toISOString();
  const to = new Date(Date.now() + 7 * 86_400_000).toISOString();
  return { from, to };
}

async function one(token, variables) {
  const started = process.hrtime.bigint();
  const response = await fetch(`${BASE}/api/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ query: QUERY, variables }),
  });
  const body = await response.text();
  const ms = Number(process.hrtime.bigint() - started) / 1e6;
  const ok = response.ok && !body.includes('"errors"');
  return { ok, ms, status: response.status };
}

function percentile(values, p) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
}

async function main() {
  const token = await login();
  const variables = window();
  let next = 0;
  let errors = 0;
  const latencies = [];
  const started = Date.now();

  async function worker() {
    while (true) {
      const index = next++;
      if (index >= REQUESTS) return;
      try {
        const result = await one(token, variables);
        latencies.push(result.ms);
        if (!result.ok) errors += 1;
      } catch {
        errors += 1;
      }
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const seconds = (Date.now() - started) / 1000;
  console.log(`requests=${REQUESTS} concurrency=${CONCURRENCY} errors=${errors}`);
  console.log(`throughput=${(REQUESTS / seconds).toFixed(1)} req/s p50=${percentile(latencies, 50).toFixed(0)} ms `
    + `p95=${percentile(latencies, 95).toFixed(0)} ms p99=${percentile(latencies, 99).toFixed(0)} ms`);
  if (errors > 0) process.exitCode = 1;
}

main().catch((error) => {
  console.error(`load: ${error.message}`);
  process.exit(1);
});
