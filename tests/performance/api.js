import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Gauge } from 'k6/metrics';

/*
 * Performance requirements P-01, P-02, P-04 and P-05.
 *
 * The test uses the real gateway and real JWT login. It is intentionally a local benchmark, not a
 * production SLO: the API, databases and k6 all share one workstation.
 *
 *   $env:PERF_BASE_URL = 'https://localhost:8443'
 *   $env:K6_PROMETHEUS_RW_SERVER_URL = 'http://localhost:9090/api/v1/write'
 *   k6 run --out experimental-prometheus-rw tests/performance/api.js
 *
 * The same test can run against Kubernetes with PERF_BASE_URL=https://localhost:30443.
 */

const BASE = (__ENV.PERF_BASE_URL || 'https://localhost:8443').replace(/\/$/, '');
const ADMIN_EMAIL = __ENV.PERF_ADMIN_EMAIL || 'admin@shift-happens.example';
const ADMIN_PASSWORD = __ENV.PERF_ADMIN_PASSWORD || 'Demo1234!';
const P05_ONLY = __ENV.PERF_P05_ONLY === 'true';
const PROFILE = __ENV.PERF_PROFILE || (P05_ONLY ? 'concurrency' : 'baseline');
const MIN_EMPLOYEES = Number(__ENV.PERF_MIN_EMPLOYEES || 1000);
const READ_VUS = Number(__ENV.PERF_READ_VUS || (P05_ONLY ? 200 : 50));
const WRITE_VUS = Number(__ENV.PERF_WRITE_VUS || (P05_ONLY ? 1 : 10));
const DURATION = __ENV.PERF_DURATION || '60s';

const SCHEDULE_QUERY = `query LoadShifts($from: DateTime!, $to: DateTime!) {
  shifts(from: $from, to: $to) {
    shiftId
    shiftName
    status
    assignments { shiftAssignmentId employee { employeeId firstName lastName } }
  }
}`;

function scheduleWindow() {
  const from = new Date(Date.now() - 7 * 86400000).toISOString();
  const to = new Date(Date.now() + 7 * 86400000).toISOString();
  return { from, to };
}

const thresholds = {
  // P-05: 200 concurrent virtual users must complete without server errors.
  'http_req_failed{workload:read}': ['rate<0.01'],
  'http_req_failed{workload:write}': ['rate<0.01'],
  checks: ['rate>0.99'],
};
if (!P05_ONLY) {
  // P-01/P-02 are measured in the baseline profile, not under the P-05 saturation run.
  thresholds.read_duration = ['p(95)<300'];
  thresholds.write_duration = ['p(95)<800'];
}

export const options = {
  insecureSkipTLSVerify: true,
  scenarios: {
    reads: {
      executor: 'constant-vus',
      vus: READ_VUS,
      duration: DURATION,
      exec: 'readScenario',
      tags: { workload: 'read' },
    },
    writes: {
      executor: 'constant-vus',
      vus: WRITE_VUS,
      duration: DURATION,
      exec: 'writeScenario',
      tags: { workload: 'write' },
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const readDuration = new Trend('read_duration', true);
const writeDuration = new Trend('write_duration', true);
const employeeCount = new Gauge('employee_count');
const readVus = new Gauge('read_vus');
const requestFailures = new Counter('request_failures');
const requests = new Counter('requests');

function authHeaders(token) {
  return {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}

function login() {
  const response = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (response.status !== 200 || !response.json('token')) {
    throw new Error(`performance login failed: HTTP ${response.status}`);
  }
  return response.json('token');
}

export function setup() {
  const token = login();
  const response = http.get(`${BASE}/api/v1/employees`, authHeaders(token));
  if (response.status !== 200) {
    throw new Error(`employee list failed: HTTP ${response.status}`);
  }
  const employees = response.json();
  const count = Array.isArray(employees) ? employees.length : 0;
  if (count < MIN_EMPLOYEES) {
    throw new Error(`P-04 fixture missing: ${count} employees, need at least ${MIN_EMPLOYEES}; `
      + 'run tests/performance/seed-performance.sql first');
  }
  employeeCount.add(count, { profile: PROFILE });
  readVus.add(READ_VUS, { profile: PROFILE });

  // Each write VU gets a separate existing employee, so concurrent PATCHes do not fight over one row.
  const writeEmployees = employees
    .filter((e) => e.employmentStatus === 'ACTIVE')
    .slice(0, Math.max(WRITE_VUS, 1))
    .map((e) => ({
      employeeId: e.employeeId,
      employeeNumber: e.employeeNumber,
      userRole: e.userRole,
      firstName: e.firstName,
      lastName: e.lastName,
      email: e.email,
      phoneNumber: e.phoneNumber,
      hireDate: e.hireDate,
      employmentStatus: e.employmentStatus,
      primaryWorkLocationId: e.primaryWorkLocationId,
    }));
  if (writeEmployees.length === 0) {
    throw new Error('no active employees available for the write scenario');
  }

  return { token, employeeCount: count, writeEmployees };
}

export function readScenario(data) {
  // P-01 uses the same authenticated GraphQL schedule read as the existing load-schedule probe.
  // The 1,000-row employee list is validated in setup as the P-04 capacity fixture, because it is a
  // full-table payload rather than a representative UI read.
  const response = http.post(
    `${BASE}/api/graphql`,
    JSON.stringify({ query: SCHEDULE_QUERY, variables: scheduleWindow() }),
    {
      ...authHeaders(data.token),
      headers: {
        ...authHeaders(data.token).headers,
        'Content-Type': 'application/json',
      },
      tags: { endpoint: 'schedule' },
    },
  );
  readDuration.add(response.timings.duration, { endpoint: 'schedule', profile: PROFILE });
  requests.add(1, { scenario: 'reads' });
  const body = response.body || '';
  const readOk = response.status === 200 && !body.includes('"errors"');
  if (!readOk) requestFailures.add(1, { scenario: 'reads' });
  check(response, {
    'schedule query returns 200': (r) => r.status === 200,
    'schedule query has no GraphQL errors': () => !body.includes('"errors"'),
  });
  sleep(0.2 + Math.random() * 0.3);
}

export function writeScenario(data) {
  const employee = data.writeEmployees[(__VU - 1) % data.writeEmployees.length];
  const body = {
    employeeNumber: employee.employeeNumber,
    userRole: employee.userRole,
    firstName: employee.firstName,
    lastName: employee.lastName,
    email: employee.email,
    phoneNumber: employee.phoneNumber,
    hireDate: employee.hireDate,
    employmentStatus: employee.employmentStatus,
    primaryWorkLocationId: employee.primaryWorkLocationId,
  };
  const response = http.patch(
    `${BASE}/api/v1/employees/${employee.employeeId}`,
    JSON.stringify(body),
    {
      ...authHeaders(data.token),
      headers: {
        ...authHeaders(data.token).headers,
        'Content-Type': 'application/json',
      },
      tags: { endpoint: 'employees' },
    },
  );
  writeDuration.add(response.timings.duration, { endpoint: 'employees', profile: PROFILE });
  requests.add(1, { scenario: 'writes' });
  if (response.status < 200 || response.status >= 300) requestFailures.add(1, { scenario: 'writes' });
  check(response, {
    'write employee update returns 2xx': (r) => r.status >= 200 && r.status < 300,
  });
  sleep(0.5 + Math.random() * 0.5);
}
