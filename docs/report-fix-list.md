# Report fix list — do these before submitting

Target: `DLS Report-2026-11-26.md`.

Full deferred requirements: [`v1.1-backlog.md`](v1.1-backlog.md).

Work top to bottom. Most boxes are 5–20 minutes.

## 1. Submit-blockers

- [ ] **Confirm the file.** Submit `DLS Report-2026-11-26.md`, not `DLS Report-2026-11-20.md`.
- [ ] **Fix owners.** In §5.2, keep the CODEOWNERS handles and decide owners for **Frontend** and **CI/CD, Kubernetes and monitoring** (currently “Unassigned, issue #32”).
- [ ] **Add one Lens screenshot** to §3.2: namespace `shift-happens`, **Workloads → Pods** or **Deployments**, with HPA/KEDA visible.
- [ ] **Add one current CI screenshot** to §3.2.2 showing the matrix (`tests`, `images`, `contracts`, `frontend`, `ci`).
- [ ] **Regenerate the TOC and page numbers.** The current TOC still has the old page numbers from before sections 6–9 were added.
- [ ] **Personalize §7 Reflection.** Add 2–3 concrete sentences per team member; keep the factual draft.
- [ ] **Decide the appendix.** Keep or remove the local demo/autoscaling runbook from the appendix; it is useful for the exam but not required in the report body.

## 2. Verify these are still true (quick read)

No rewrite needed; just confirm the final PDF says this:

- [ ] §3.2 uses **Docker Desktop Kubernetes**, plain `kubernetes/` YAML, Caddy `api-gateway` **30443**.
- [ ] §3.2.3 says **no distributed tracing** and points to S-05.
- [ ] §3.2.4 keeps the measured numbers: **41,808 backlog**, **1 → 3 → 4** pods, queue **0** by **15:23:30**, back to **1** by **15:26:30**.
- [ ] §3.2.4 says this is **one node**, not multi-node/99.9% availability.
- [ ] §3.3 says the Azure plan is a **plan, not deployed**.
- [ ] §6.4 says **39 met / 18 partial / 6 not met / 2 not verified**.
- [ ] §8 does **not** claim all 65 requirements are met.

## 3. Remove or qualify risky claims

- [ ] Search the report for `kind`, `minikube`, `Kustomize`, `Helm`, `Tempo`, `OpenRouter`, `six microservices`, `TBD`.
  - Expected: no hits outside the intentional “does not depend on Kustomize or Helm” sentence and the v1.1 limitations.
- [ ] Search for `99.9`, `1,000 employee`, `200 concurrent`, `distributed tracing`.
  - Each hit must be a **limitation/target**, never a result.
- [ ] Search for `will be`.
  - Change future/plan language to present or past where the work is already done.
- [ ] Confirm no “all 65 product requirements are met” sentence exists.

## 4. Deferred v1.0 scope — include it honestly

Summary to keep in §6.4/§8: **39 met, 18 partial, 6 not met, 2 not verified**. Do not claim all 65.

### Not met (6) — say “planned for v1.1”

- [ ] **F-17** — employee-facing open/claimable swap list.
- [ ] **U-01** — responsive/mobile shift planner.
- [ ] **R-01** — 99.9% availability; needs multi-zone AKS, not this laptop.
- [ ] **P-03** — notifications within 5 s; current bell polls every 30 s.
- [ ] **S-05** — distributed tracing; metrics/logs only today.
- [ ] **O-04** — automated daily database backups/PITR.

### Not verified (2) — say “not measured”

- [ ] **P-02** — write API p95 under 800 ms; no write benchmark yet.
- [ ] **P-04** — 1,000 employees; seed has 13, benchmark uses 500.

### Partial (18) — mention by ID, do not overclaim

The six not-met and two not-verified items are listed above; the groups below contain the remaining 18 partial items.

- [ ] **Frontend/UX (5):** F-07, F-08, F-12, F-22, U-03 — missing job-role/work-location pages, department/location filters and field-level validation.
- [ ] **Authorization (2):** F-03, F-14 — leave reads are broad; Employees must get own-scope reads.
- [ ] **Notifications/AI (4):** F-18, F-20, F-21, U-04 — swap eligibility picker, shift-update notifications, understaffed-on-create and preferences.
- [ ] **Reliability/security (4):** R-03, R-06, A-04, SE-01 — liveness/PDB/replicas, publisher confirms, ingress leave route and TLS.
- [ ] **Logs (1):** S-04 — JSON logs; current logs are plain text.
- [ ] **Performance/scale (2):** P-01, P-05 — read p95 at 200 users and 200 distinct users.

## 5. Fast verification commands

```powershell
python scripts/check-docs.py
```

Expected: `yaml: 0 failure(s)` and `links: 0 failure(s)`.

```powershell
Test-Path docs/images/observability/autoscaling-queue-vs-pods.png
Select-String -Path "docs/DLS Report-2026-11-26.md" -Pattern 'TBD|OpenRouter|kind/minikube|six microservices'
```

Expected: image exists; no accidental stale claims.

```powershell
git diff --check
```

Expected: no whitespace errors (CRLF warnings are fine).

## 6. Done when

- [ ] All seven submit-blockers are checked.
- [ ] The TOC/page numbers match the final PDF.
- [ ] The deferred section is visible in §6.4 and §8.
- [ ] `check-docs.py` passes.
- [ ] You exported `DLS Report-2026-11-26.md`, not the 11-20 file.
