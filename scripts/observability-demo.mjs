#!/usr/bin/env node
// A local demo. Creates and cancels an assignment on its own seeded test shift.
import { randomBytes, randomUUID } from 'node:crypto';
import { setTimeout as wait } from 'node:timers/promises';

const gateway = new URL(process.env.TRACE_DEMO_GATEWAY ?? 'https://localhost:8443');
const tempo = new URL(process.env.TRACE_DEMO_TEMPO ?? 'http://localhost:3200');
const localHosts = new Set(['localhost', '127.0.0.1', '[::1]']);
if (![gateway, tempo].every((url) => localHosts.has(url.hostname) && ['http:', 'https:'].includes(url.protocol))) {
  throw new Error('Use the local Compose gateway or Kubernetes with port forwarding.');
}
// The local gateway serves its own TLS certificate.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const password = process.env.TRACE_DEMO_PASSWORD;
if (!password) throw new Error('Set TRACE_DEMO_PASSWORD to the seeded Manager password.');
const email = process.env.TRACE_DEMO_EMAIL ?? 'sofie.jensen@shift-happens.example';

async function request(method, path, { token, body, headers = {} } = {}) {
  const options = {
    method,
    headers: { Accept: 'application/json', ...headers },
    redirect: 'error',
    signal: AbortSignal.timeout(15_000),
  };
  if (token) options.headers.Authorization = `Bearer ${token}`;
  if (method === 'POST') options.headers['Idempotency-Key'] = randomUUID();
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  const response = await fetch(new URL(`/api${path}`, gateway), options);
  if (!response.ok) throw new Error(`${method} ${path} failed (HTTP ${response.status}).`);
  if (response.status === 204) return null;
  const payload = await response.text();
  return payload ? JSON.parse(payload) : null;
}

async function findDemoTrace(traceId, correlationId) {
  // Search for a span from each app in the same trace. The correlation ID is independent of the trace ID.
  const queries = ['scheduling-command', 'ai-service'].map((service) => {
    const url = new URL('/api/search', tempo);
    url.searchParams.set('q', `{ resource.service.name = "${service}" && span.app.correlation_id = "${correlationId}" }`);
    return url;
  });
  const deadline = Date.now() + 150_000;
  while (Date.now() < deadline) {
    const found = await Promise.all(queries.map(async (url) => {
      const response = await fetch(url, { signal: AbortSignal.timeout(5_000), redirect: 'error' });
      if (!response.ok) throw new Error(`Tempo search failed (HTTP ${response.status}).`);
      return (await response.json()).traces?.some((trace) => trace.traceID === traceId);
    }));
    if (found.every(Boolean)) return;
    await wait(2_000);
  }
  throw new Error('Tempo did not return both application spans in the demo trace within 150 seconds.');
}

async function main() {
  const login = await request('POST', '/v1/auth/login', { body: { email, password } });
  const { token } = login ?? {};
  if (!token) throw new Error('The local Manager login did not return a token.');
  const [employees, departments, locations, roles] = await Promise.all(
    ['employees', 'departments', 'worklocations', 'employeejobroles']
      .map((name) => request('GET', `/v1/${name}`, { token }))
  );
  const active = new Set(employees.filter((employee) => employee.employmentStatus === 'ACTIVE')
    .map((employee) => employee.employeeId));
  const membersByRole = new Map();
  for (const { employeeId, jobRoleId } of roles) {
    if (!active.has(employeeId)) continue;
    if (!membersByRole.has(jobRoleId)) membersByRole.set(jobRoleId, new Set());
    membersByRole.get(jobRoleId).add(employeeId);
  }
  const role = [...membersByRole].find(([, members]) => members.size >= 2);
  if (!role || !departments.length || !locations.length) {
    throw new Error('Load demo data with scripts/seed-dev.sh and wait for the employee projection.');
  }
  const [jobRoleId, employeesForRole] = role;
  const [employeeId] = employeesForRole;
  const correlationId = `observability-demo-${randomUUID()}`;
  const start = new Date(Date.now() + 86_400_000 * (7 + randomBytes(2).readUInt16BE() % 365));
  const end = new Date(start.getTime() + 8 * 3_600_000);
  const shift = await request('POST', '/v1/shifts', { token, body: {
    departmentId: departments[0].departmentId, workLocationId: locations[0].workLocationId,
    shiftName: `Trace demo ${correlationId.slice(-8)}`, shiftStatus: 'PLANNED',
    startDatetime: start.toISOString(), endDatetime: end.toISOString(),
    requiredJobRoles: [{ jobRoleId, requiredEmployeeCount: 1 }],
  } });
  const assignment = await request('POST', '/v1/shiftassignments', { token, body: {
    shiftId: shift.shiftId, employeeId, assignmentStatus: 'ASSIGNED',
  } });
  const traceId = randomBytes(16).toString('hex');
  const parentId = randomBytes(8).toString('hex');
  await request('PUT', `/v1/shiftassignments/${assignment.shiftAssignmentId}`, {
    token,
    headers: { 'X-Correlation-Id': correlationId, traceparent: `00-${traceId}-${parentId}-01` },
    body: { shiftId: shift.shiftId, employeeId, assignmentStatus: 'CANCELLED' },
  });
  console.log(`Cancelled demo assignment ${assignment.shiftAssignmentId} on shift ${shift.shiftId}.`);
  console.log(`Trace ID: ${traceId}\nCorrelation ID: ${correlationId}`);
  await findDemoTrace(traceId, correlationId);
  console.log('Tempo found Scheduling and AI spans with the same trace and correlation IDs.');
}

main().catch((error) => {
  console.error(`Trace demo: ${error.message}`);
  process.exitCode = 1;
});
