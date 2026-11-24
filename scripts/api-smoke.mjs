#!/usr/bin/env node
/**
 * Dev-only smoke test against a running Compose stack: logs in, then drives one full business chain
 * through the gateway (shift -> required role -> assignment -> swap -> approval -> leave type ->
 * leave request -> leave approval). It prints every step and exits non-zero on the first failure.
 *
 *   node scripts/api-smoke.mjs
 *   E2E_BASE_URL=https://localhost:8443 node scripts/api-smoke.mjs
 *
 * The stack uses Caddy's self-signed certificate, so TLS verification is off for this script only.
 * Credentials are the seeded demo accounts (docs/manual-testing.md).
 */
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const BASE = (process.env.E2E_BASE_URL ?? 'https://localhost:8443').replace(/\/$/, '');
const PASSWORD = process.env.E2E_PASSWORD ?? 'Demo1234!';
const MANAGER = { email: 'sofie.jensen@shift-happens.example', password: PASSWORD };
const ADMIN = { email: 'admin@shift-happens.example', password: PASSWORD };
const stamp = Date.now().toString().slice(-6);
// Every run gets its own day, so repeated smoke runs do not collide with the overlap rules.
const shiftDay = new Date(Date.UTC(2035, 0, 1 + (Math.floor(Date.now() / 1000) % 3000)));
const shiftDate = shiftDay.toISOString().slice(0, 10);
const leaveDay = new Date(Date.UTC(2036, 0, 1 + (Math.floor(Date.now() / 1000) % 3000)));
const leaveStart = leaveDay.toISOString().slice(0, 10);
const leaveEnd = new Date(leaveDay.getTime() + 86_400_000).toISOString().slice(0, 10);

async function call(token, method, path, body) {
  const headers = { Accept: 'application/json, application/problem+json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method === 'POST') headers['Idempotency-Key'] = crypto.randomUUID();
  const response = await fetch(`${BASE}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* not JSON */
  }
  return { status: response.status, json, text };
}

function ok(step, result, statuses = [200, 201, 202, 204]) {
  if (!statuses.includes(result.status)) {
    throw new Error(`${step} failed: HTTP ${result.status} ${result.text}`);
  }
  console.log(`ok   ${step} (${result.status})`);
  return result.json;
}

async function login(account) {
  const result = await call(null, 'POST', '/v1/auth/login', account);
  const body = ok(`login ${account.email}`, result, [200]);
  return body.token;
}

async function main() {
  console.log(`base ${BASE}`);
  const manager = await login(MANAGER);
  const admin = await login(ADMIN);

  const [employees, departments, locations, jobRoles] = await Promise.all([
    call(manager, 'GET', '/v1/employees').then((r) => ok('list employees', r)),
    call(manager, 'GET', '/v1/departments').then((r) => ok('list departments', r)),
    call(manager, 'GET', '/v1/worklocations').then((r) => ok('list work locations', r)),
    call(manager, 'GET', '/v1/jobroles').then((r) => ok('list job roles', r)),
  ]);
  if (!employees.length || !departments.length || !locations.length || !jobRoles.length) {
    throw new Error('demo data missing: run scripts/seed-dev.sh');
  }

  // Two active employees that share a job role are needed for a swap.
  const jobRoleId = jobRoles[0].jobRoleId;
  const allRoles = ok('list employee job roles', await call(manager, 'GET', '/v1/employeejobroles'));
  const people = employees
    .filter((e) => e.employmentStatus === 'ACTIVE')
    .map((e) => e.employeeId)
    .filter((employeeId) => allRoles.some((row) => row.employeeId === employeeId && row.jobRoleId === jobRoleId));
  if (people.length < 2) throw new Error(`need two employees with job role ${jobRoleId}`);
  const [from, to] = people;

  const shift = ok('create shift', await call(manager, 'POST', '/v1/shifts', {
    departmentId: departments[0].departmentId,
    workLocationId: locations[0].workLocationId,
    shiftName: `Smoke ${stamp}`,
    startDatetime: `${shiftDate}T09:00:00Z`,
    endDatetime: `${shiftDate}T17:00:00Z`,
    shiftStatus: 'PUBLISHED',
    requiredJobRoles: [{ jobRoleId, requiredEmployeeCount: 1 }],
  }));

  const assignment = ok('assign employee', await call(manager, 'POST', '/v1/shiftassignments', {
    shiftId: shift.shiftId,
    employeeId: from,
    assignmentStatus: 'ASSIGNED',
  }));

  // The contract makes the staffing plan optional: a shift can be created first and staffed later.
  ok('create shift without staffing plan', await call(manager, 'POST', '/v1/shifts', {
    departmentId: departments[0].departmentId,
    workLocationId: locations[0].workLocationId,
    shiftName: `Smoke unstaffed ${stamp}`,
    startDatetime: `${shiftDate}T18:00:00Z`,
    endDatetime: `${shiftDate}T20:00:00Z`,
    shiftStatus: 'PLANNED',
  }));

  const swap = ok('request swap', await call(manager, 'POST', '/v1/shiftswaps', {
    originalShiftAssignmentId: assignment.shiftAssignmentId,
    employeeFromId: from,
    employeeToId: to,
    reason: 'smoke test',
  }));

  ok('approve swap', await call(manager, 'POST', '/v1/shiftswapapprovals', {
    shiftSwapId: swap.shiftSwapId,
    approverEmployeeId: 2,
    decision: 'APPROVED',
    shiftSwapComment: 'smoke',
  }));

  const leaveType = ok('create leave type', await call(manager, 'POST', '/v1/leavetypes', {
    leaveTypeName: `Smoke leave ${stamp}`,
    leaveTypeDescription: 'smoke',
    requiresApproval: true,
    isPaidLeave: true,
  }));

  // A new leave type starts with no balance; approving without an accrual is a 409 by design.
  ok('grant leave days', await call(manager, 'POST', '/v1/leaveledgers', {
    employeeId: from,
    leaveTypeId: leaveType.leaveTypeId,
    changeAmountDays: 25,
    transactionType: 'ACCRUAL',
    referenceEntityType: 'smoke',
    referenceEntityId: null,
  }));

  const leaveRequest = ok('request leave', await call(manager, 'POST', '/v1/leaverequests', {
    employeeId: from,
    leaveTypeId: leaveType.leaveTypeId,
    startDate: leaveStart,
    endDate: leaveEnd,
    reason: 'smoke test',
  }));

  const approval = ok('approve leave', await call(manager, 'POST', '/v1/leaveapprovals', {
    leaveRequestId: leaveRequest.leaveRequestId,
    approverEmployeeId: 2,
    decision: 'APPROVED',
    leaveComment: 'smoke',
  }));
  console.log(`     leave request ${leaveRequest.leaveRequestId} is ${approval.requestStatus ?? 'accepted'}`);

  const audit = await call(admin, 'GET', `/v1/auditlogs?aggregateId=${shift.shiftId}`);
  ok('read audit log', audit);

  console.log('smoke: all steps passed');
}

main().catch((error) => {
  console.error(`smoke: ${error.message}`);
  process.exit(1);
});
