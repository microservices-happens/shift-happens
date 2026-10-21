# Professor Meeting Prep — Software Quality Exam

Project: **Shift-Happens** (shift scheduling system)
Stack: Java 21 / Spring Boot, MySQL, React + TypeScript

---

## A. Using Our Database Project

1. We plan to fork our database project (Shift-Happens) and remove the
   MongoDB + Neo4j layers, keeping only the MySQL backend and React frontend.
   Is this a valid approach for the exam? Anything we should watch out for?

2. The project already has some business logic (leave approval role checks,
   contract date validation via DB triggers, leave balance calculation via a
   stored function). Is it acceptable to write tests against this existing logic,
   or do you expect us to build new features specifically for testing purposes?

3. A significant portion of our business logic lives in the MySQL database
   as triggers and stored functions rather than in the Java service layer.
   Examples:
   - Contract validation (salary > 0, no overlapping contracts, start ≤ end date)
   - Leave balance enforcement (stored function `fn_leave_balance` + trigger)
   - Password length enforcement (trigger on employee insert/update)

   JaCoCo (our code coverage tool) cannot measure coverage on SQL triggers or
   functions — they are invisible to it. Unit tests with Mockito also cannot
   reach this logic since they bypass the database entirely.

   **Should we refactor this DB-level business logic into the Java service layer**
   so it can be unit tested and appear in the coverage report? Or is it acceptable
   to leave it in the DB and cover it only via integration tests (Testcontainers)?

   Our concern: if we refactor, we risk introducing duplicate logic (both Java
   and the DB trigger would enforce the same rule), which can cause confusion
   in integration tests. But if we don't, our coverage report will show a gap
   for core business rules.

4. The exam requires a connection to an external public API. Our project
   currently does not have one. How integrated does this need to be — is
   something like a simple lookup (e.g., public holidays, weather, postal code)
   that the backend proxies to the frontend sufficient, or do you expect the
   external data to drive business logic in the application?

---

## B. Test Quantities and Expectations

4. Is there a minimum number of unit tests expected? Our current plan targets
   ~25–35 parameterized unit test cases across service classes.

5. Is there a minimum number of integration tests? We plan 3 `@SpringBootTest`
   tests using Testcontainers (real MySQL container) to cover: leave approval
   flow, contract overlap detection, and shift assignment timing rules.

6. For API testing (Postman/Newman), how many endpoints should be covered?
   The project has ~60+ endpoints. Should we cover all of them or focus on the
   most meaningful ones with positive + negative cases?

7. For E2E tests — how many user flows / test cases are expected? We plan
   Playwright tests for: login, dashboard, leave request form, shift planner.

8. For stress testing — is documenting the results (screenshots + PDF) enough,
   or do you expect the performance tests to also run in the CI pipeline?

---

## C. Static Analysis Findings

9. We plan to use SonarCloud (which you showed us) as our primary static analysis
   tool. Is it acceptable to also add a second tool like SpotBugs (a Maven plugin
   for bug pattern detection) to show two perspectives in the static analysis
   deliverable, or is one tool sufficient?

10. SonarCloud will likely flag security hotspots in our project — for example,
    hardcoded secrets in dev config files, missing security headers, or JWT
    handling patterns. Should we fix all findings before the exam, or is it
    acceptable to document and explain them (e.g., "this is a dev-only config,
    not present in production")?

---

## D. Test Environment and Infrastructure

11. For integration tests we plan to use Testcontainers — each CI run spins up
    a real MySQL 8.0 Docker container, runs the full schema and triggers against
    it, then tears it down. This gives us a clean, isolated environment on every
    run without a shared staging database.
    Do you expect anything beyond this — e.g., a deployed staging environment
    that the API tests and E2E tests run against, or is the Docker-based approach
    sufficient for all test types?

12. The exam says "include coverage calculation" but doesn't state a minimum
    percentage. Is there a coverage threshold you expect us to meet, or is the
    goal simply to document and analyse whatever coverage we achieve?

---

## E. Scope and Deliverables

13. We have identified ~30 potential unit test scenarios. Is it better to
    implement all of them shallowly, or implement fewer but with thorough
    parameterization, EP, and BVA coverage?

14. For the SRS — should it document the full application or just the features
    we test? (The app has 18 entities and ~60 endpoints, which would make a
    very large SRS.)

15. For the state transition diagrams — are these expected to cover all entity
    states in the system, or just the states where we apply test cases?
