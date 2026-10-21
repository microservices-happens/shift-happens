# Shift-Happens — Software Quality Exam Execution Plan

## Current State vs. Exam Requirements

| Requirement | Status | Gap |
|---|---|---|
| Frontend | ✅ React 18 + TS exists | Needs E2E test coverage |
| Backend API | ✅ ~60 endpoints exist | Needs external API + new service validations |
| Database | ✅ MySQL with triggers/functions | Fine as-is |
| External public API | ❌ Missing | Add public holiday proxy endpoint |
| Unit tests | ⚠️ 5 tests only | Expand to ~30 parameterized cases |
| Integration tests | ❌ None | Add 3 Testcontainers-based IT tests |
| CI pipeline | ❌ None | GitHub Actions (3 jobs) |
| API tests | ⚠️ Collection exists, no test scripts | Add assertions + negative tests |
| E2E tests | ❌ None | Playwright (4 flows) |
| Performance tests | ❌ None | k6 (load, stress, spike) |
| Static analysis | ❌ None | SpotBugs + JaCoCo |

---

## Phase 1 — Fork Cleanup (Day 1, 1 person ~2 h)

Remove from the fork:
- All `*/mongo/` sub-packages inside every feature package
- All `*/neo4j/` sub-packages
- `migration/` package (MigrationController, MigrationService, Neo4jMigrationService)
- `config/Neo4jConfig.java` and `graph/` package
- MongoDB + Neo4j starters from `pom.xml`
- Neo4j env vars from `application.properties`
- `src/main/resources/db/mongodb/` and `src/main/resources/db/neo4j/`

**Verify:** `./mvnw spring-boot:run` boots cleanly against MySQL only.

---

## Phase 2 — Add External API (Day 1–2, TBD after professor meeting)

**Confirm choice with professor first** — ask how integrated it needs to be.

Once decided, add a new package (e.g., `dk.ek.shift_happens.holiday` or similar) with:
- A `@Service` that calls the external API via `RestClient`
- A controller endpoint that proxies the result to the frontend
- A frontend card on `DashboardPage.tsx` that displays the data

Good criteria for the choice: no API key required (avoids CI secrets), relevant to
shift scheduling domain, gives a mockable HTTP boundary for unit tests, and gives
E2E a concrete element to assert on.

---

## Phase 3 — Add Business Logic for Test Surface (Week 1, Days 2–5)

Three service classes to add (pure Java — no schema changes needed):

### A. `EmployeeContractService.java`
(No service exists today — controller calls repo directly)
- `salary > 0` and `weeklyHours > 0` validation
- `startDate <= endDate` check
- Active contract overlap detection for same employee

### B. `ShiftAssignmentService.java`
(Extract from controller, add timing rules)
- Check-in must not be earlier than 30 min before shift start
- Check-out must not be later than 12 h after shift end
- Duplicate assignment guard (same shift + employee)

### C. `EmployeeService.java`
- Add email format regex validation to `save()` and `patch()`

---

## Phase 4 — Unit Tests (Week 2, Days 1–3)

Target ~30–35 parameterized cases total.

| Test Class | Approx. Cases | Black-Box Technique |
|---|---|---|
| `LeaveRequestServiceTest` (expand) | +4 | EP, BVA on dates |
| `LeaveApprovalServiceTest` (expand) | +2 | Decision table |
| `EmployeeContractServiceTest` | 8–10 | EP + BVA on salary/hours/dates |
| `ShiftAssignmentServiceTest` | 6 | BVA on 30-min / 12-h timing boundaries |
| `EmployeeServiceTest` | 4 | EP on email format |
| `ExternalApiClientTest` | 3 | Mock HTTP client (API TBD after professor meeting) |

Framework: JUnit 5, `@ParameterizedTest` + `@CsvSource`, Mockito.

---

## Phase 5 — Integration Tests (Week 2, Days 2–4)

**Strategy: Testcontainers + MySQL 8.0**
H2 is not viable — the project uses MySQL-specific triggers and `SIGNAL SQLSTATE` syntax.

Add to `pom.xml` (test scope): `org.testcontainers:mysql`

Create `AbstractMySQLIntegrationTest.java`:
- `@Testcontainers`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@DynamicPropertySource` overrides datasource URL/user/pass
- Init scripts: `schema.sql` + `v3_leave_approval_trigger.sql` + `v4_triggers.sql` + `v4_stored_functions.sql`

Three `*IT.java` classes extending the base:
1. `LeaveApprovalIT` — create employee → leave request → approve → assert status sync + ledger entry
2. `EmployeeContractIT` — create contract, attempt active overlap, expect DB trigger to throw
3. `ShiftAssignmentIT` — create shift + employee, test check-in timing boundary via service

---

## Phase 6 — CI Pipeline (Week 2, Days 4–5)

**File:** `.github/workflows/ci.yml`

### Job 1: `test-backend`
- Service container: `mysql:8.0` (hard-coded ephemeral test credentials)
- Steps: checkout → setup-java 21 → Maven cache → `mvn test`
- Artifacts: Surefire XML + JaCoCo HTML report

### Job 2: `api-test`
- Steps: checkout → `docker-compose up -d` → poll `/actuator/health` → Newman run
- Artifact: Newman HTML report

### Job 3: `e2e`
- Steps: checkout → `docker-compose up -d` → `npm ci` (frontend) → `npx playwright install --with-deps` → `npx playwright test`
- Artifact: Playwright HTML report

**Performance tests:** on-demand via `workflow_dispatch` — NOT in the PR pipeline.

---

## Phase 7 — API Tests (Week 2–3)

Expand the existing Postman collection (`src/main/resources/postman/`):
- Every endpoint: positive test (200/201) + at least one negative (400/401/403/404)
- `pm.test("status code", ...)` + `pm.test("response time < 500ms", ...)` on all requests
- Add two environment files: `local.postman_environment.json` + `ci.postman_environment.json`
- CI runs via Newman CLI

---

## Phase 8 — E2E Tests with Playwright (Week 2–3)

Location: `frontend/e2e/`
Config: `frontend/playwright.config.ts` — `baseURL: http://localhost:5173`, `webServer: npm run dev`

| File | Scenarios |
|---|---|
| `auth.spec.ts` | Login valid → dashboard; Login wrong password → error message |
| `dashboard.spec.ts` | Holidays card renders; shift summary visible |
| `leave-request.spec.ts` | Fill + submit form → appears in list with status PENDING |
| `shift-planner.spec.ts` | Manager creates shift → appears in weekly planner |

---

## Phase 9 — Performance Tests with k6 (Week 3)

Location: `performance/` at repo root. All scripts authenticate via `POST /auth/login` and reuse the JWT.

| File | Description |
|---|---|
| `load-test.js` | 50 VU × 2 min on `GET /shifts` + `GET /shiftassignments`. Assert p95 < 500 ms, error rate < 1% |
| `stress-test.js` | Ramp 0 → 200 VU over 3 min, hold 1 min. Find breaking point. |
| `spike-test.js` | 10 VU baseline → spike to 150 VU for 30 s → recover. Measure recovery time. |

Run locally against `docker-compose` backend. Document results as screenshots in the PDF deliverable.

---

## Phase 10 — Static Analysis (Week 3)

**Primary tool: SonarCloud** (what the professor taught — SAST/code quality)
- Connect the GitHub repo to SonarCloud
- Add the `sonar-maven-plugin` to `pom.xml` and configure the project key + org
- Run via CI: `mvn sonar:sonar -Dsonar.token=$SONAR_TOKEN`
- SonarCloud dashboard shows code smells, security hotspots, duplications, and pulls in JaCoCo coverage automatically
- Export the SonarCloud report page as screenshots for the PDF deliverable

**Optional second tool: SpotBugs** (bug pattern detection — complements SonarCloud)
- Add `spotbugs-maven-plugin` to `pom.xml`
- Run: `mvn spotbugs:spotbugs` → `target/spotbugsXml.xml`
- Adds a second data point to the static analysis PDF section

**Coverage:**
- Add JaCoCo plugin to `pom.xml`
- Run: `mvn jacoco:report` → `target/site/jacoco/index.html`
- SonarCloud can import the JaCoCo XML report directly, so coverage shows in the dashboard
- Target: >70% line coverage on the service layer

Note: SonarCloud and SpotBugs are both **SAST** tools (analyze source code without running the app).
The exam does not require DAST tools.

---

## Phase 11 — PDF Deliverables (Week 3–4)

| # | Deliverable | Suggested Owner | Source |
|---|---|---|---|
| 1 | SRS PDF | Member A | Scope to features being tested — user stories or use cases |
| 2 | Formal review report | Members B+C+D | Structured checklist review of the SRS |
| 3 | Risk assessment (initial + mid + final) | Member B | Risk tables + matrices at 3 project stages |
| 4 | Black-box design (EP, BVA, decision table, state diagrams) | Member C | See sources below |
| 5 | Static analysis + coverage report | Member D | SpotBugs + JaCoCo screenshots |
| 6 | CI output | All | Screenshot of GitHub Actions green run |

---

## Black-Box Test Design Sources

### EP + BVA — `LeaveRequestService`
| Partition | Input | Expected |
|---|---|---|
| EP1 valid | startDate = endDate | Accept |
| EP2 valid | startDate < endDate | Accept |
| EP3 invalid | startDate > endDate | Reject |
| BVA inside | start = end − 1 day | Accept |
| BVA boundary | start = end | Accept |
| BVA outside | start = end + 1 day | Reject |
| EP4 invalid | employeeId = null | Reject |
| EP5 invalid | leaveTypeId = null | Reject |

### Decision Table — `LeaveApprovalService`
Conditions: approver role (Admin/Manager/Employee) × request status (PENDING/other) × decision (APPROVED/REJECTED/invalid)

### State Transition — Leave Request
```
[created] → PENDING
PENDING   → APPROVED   (guard: approver is Admin or Manager)
PENDING   → REJECTED   (guard: approver is Admin or Manager)
[any non-PENDING] → transition attempt = error
```

### State Transition — Shift Assignment
```
[created] → ASSIGNED
ASSIGNED  → CHECKED_IN   (guard: check-in not earlier than 30 min before shift start)
CHECKED_IN → CHECKED_OUT  (guard: check-out not later than 12 h after shift end)
CHECKED_OUT → COMPLETED
```

---

## What NOT to Build

| Item | Reason to skip |
|---|---|
| Leave balance check in Java service layer | Already enforced by DB trigger `trg_leaveapproval_before_insert`. Duplicating it creates test confusion. |
| Phone number regex validation | Low exam value, no meaningful technique demonstration |
| Department deactivation blocking shifts | Requires service layer refactor with breakage risk during crunch |
| Vitest for React frontend unit tests | E2E already covers frontend; a 10th test type adds no exam points |
| Selenium IDE scripts | Explicitly forbidden by the exam requirements |
| Performance tests in PR pipeline | Need seeded data + warm JVM; put behind `workflow_dispatch` to avoid flaky failures |

---

## Critical Files to Modify

| File | Change |
|---|---|
| `pom.xml` | Add JaCoCo, SpotBugs, Testcontainers, spring-boot-starter-actuator |
| `.../employeecontract/EmployeeContractController.java` | Delegate to new `EmployeeContractService` |
| `.../shiftassignment/ShiftAssignmentController.java` | Delegate to new `ShiftAssignmentService` |
| `.../employee/EmployeeService.java` | Add email format validation |
| `frontend/src/pages/DashboardPage.tsx` | Add `PublicHolidaysCard` component |
| `src/main/resources/application.properties` | Expose `/actuator/health` (for CI wait-on) |

---

## Team Division (4 members)

| Member | Primary Track |
|---|---|
| A | Fork cleanup + `EmployeeContractService` + its unit tests + SRS PDF |
| B | `ShiftAssignmentService` + its unit tests + risk assessment PDF |
| C | `HolidayClient` + integration tests (`*IT.java`) + black-box design PDF |
| D | Frontend holiday card + Playwright E2E + static analysis PDF + CI pipeline |

---

## End-to-End Verification Checklist

- [ ] `./mvnw test` — all unit tests green + JaCoCo report at `target/site/jacoco/`
- [ ] `./mvnw verify` — Testcontainers IT tests green
- [ ] `mvn spotbugs:spotbugs` — SpotBugs report generated
- [ ] `docker-compose up -d` → Newman run → all Postman tests green
- [ ] `cd frontend && npx playwright test` — all E2E tests green
- [ ] Push to GitHub → CI pipeline green on all 3 jobs
- [ ] k6 load test run locally — results documented in PDF
