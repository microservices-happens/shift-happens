# Report audit and remaining fixes

This is the review list for `docs/DLS Report-2026-11-26.md`. It was produced by comparing the report against the repository, the running Kubernetes/Compose stacks and the v1.1 audit in [`v1.1-backlog.md`](v1.1-backlog.md).

## Fixed in the report

| Severity | Problem | Fix |
|---|---|---|
| Critical | A large block of unrelated material was appended after section 5: a security exercise about products/comments/sessions, a “Secure API” exercise about `/projects` and `/submissions`, raw “Claude” output for another assignment, and an old development-environment checklist marked “Not ready”/“Not done”. | Removed the entire block and replaced it with real Discussion, Reflection, Conclusion and References sections (6–9). |
| Critical | The report stopped at section 5; Discussion, Reflection, Conclusion and References were only outline headings. | Added substantial sections 6–9 based on the implementation, measurements and the v1.1 backlog. |
| High | Section 3.2 described `kind`/`minikube`, a `k8s/` folder, Kustomize, Helm and NGINX Ingress. The delivered stack is Docker Desktop Kubernetes with plain YAML in `kubernetes/`, a Caddy `api-gateway` NodePort 30443 and an optional, currently incomplete ingress. | Rewrote §3.2 and §3.2.1; described the actual entry point, manifests, secrets, databases and add-ons. |
| High | §3.2.3 claimed OpenTelemetry traces to Tempo and vendor-neutral trace export. No service has a tracing exporter and Tempo is not running. | Rewrote as Micrometer/Prometheus/Loki/Grafana, correlation-id log tracing, Lens and kube-state-metrics; explicitly marks distributed tracing as v1.1 (S-05). |
| High | §3.2.4 said HPA 1–5 on query roles and KEDA on the AI service. Actual HPA is 2–5 at 60/70%; KEDA scales `notification-service` and the email ScaledJob. | Corrected the targets and added the measured 50,000-event autoscaling experiment and graph. |
| High | §3.2.2 claimed an automatic deploy to the local cluster and made SonarQube/Trivy blocking without qualification. | Corrected to the actual workflow: tests, images, contracts, frontend, CodeQL, conditional Sonar, GHCR push, explicit `kubectl apply`. |
| High | §1.3 and the Deployment Strategy still said the architecture “will be” built and Railway was the primary cloud. | Rewrote in present tense and aligned the strategy with the implemented local stacks and the Azure §3.3 plan. |
| Medium | §2.3 said AI calls OpenRouter. The implementation uses local Ollama with a rule-based fallback. | Corrected the communication table. |
| Medium | §2.1 said the system uses relational databases, ignoring the MongoDB read models. | Rewrote to PostgreSQL write models + MongoDB CQRS read models. |
| Medium | D-01 said “six microservices” while listing seven. | Corrected to seven backend microservices. |
| Medium | §4.1/§4.2 used future tense (“will be introduced”, “could be used”); §4.6/§4.8 overstated or understated the test/static-analysis evidence. | Rewrote to present tense, added the actual Testcontainers scope, Playwright E2E and the conditional Sonar/Trivy gates. |
| Low | TOC still linked to the removed Security/Claude sections. | Replaced with sections 6–9. |

## Remaining author tasks

These are not code defects; they need a team decision or a screenshot before the PDF export.

| Task | Why it remains |
|---|---|
| Confirm the submitted file is `DLS Report-2026-11-26.md` | `DLS Report-2026-11-20.md` is still in `docs/`; do not export the older copy by mistake. |
| Confirm the owner handles in §5.2 | The table now mirrors `.github/CODEOWNERS`; frontend and CI/CD are still unassigned (issue #32), so the team must decide who owns them. |
| Add the Kubernetes/Lens screenshots for §3.2 | The text now points at the actual stack, but the report would be stronger with a Lens namespace view and the `kubectl get pods`/HPA output. `docs/images/observability/autoscaling-queue-vs-pods.png` is already included. |
| Add a CI pipeline screenshot | §3.2.2 references the pipeline diagram placeholder; the existing `image6`/`image7` show a broken/fixed pipeline but not the current matrix. |
| Proofread page numbers and regenerate the TOC | The report is Markdown exported from a document; page numbers in the TOC are still the old ones. |
| Personalize §7 Reflection | The draft is factual and based on the repository, but each team member should add their own experience. |
| Decide whether to keep the local demo section in the appendix | It is useful for the exam but not required in the report body. |
| State the v1.1 scope explicitly in the conclusion | The draft already lists the gaps; confirm the team is comfortable with that wording. |

## Claims the report must not make

- All 65 product requirements are met. The audit is 39 met, 18 partial, 6 not met, 2 not verified.
- 99.9% availability or production HA from the single-node Docker Desktop cluster.
- Distributed tracing. Logs carry correlation ids, but no trace spans cross services.
- 200 concurrent users at a 300 ms p95 target. The measured 200-concurrency p95 was 1,883 ms.
- A 1,000-employee dataset or a write-path p95 benchmark has been run.
- Automated daily database backups exist.
- The optional `kubernetes/ingress.yaml` is correct; its leave routes still send GETs to `leave-command`.
- Employees can browse open swap requests; v1.0 only supports targeted swaps.
