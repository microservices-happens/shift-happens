## Development of Large Systems Final Project

## Project description

The goal of this project is to develop a system with a distributed architecture suitable for large IT systems. We are aiming for characteristics like:

- Scalability:

- o databases: amount of data, number of requests, query optimization.

- o application: microservices architecture enabling the use of Kubernetes to orchestrate the containerized microservices.

- o Proper API design enabling easy extensions and modifications.

- Portability: containerization  each microservice is shipped with all the dependencies and runtime environments.

- Security: Authentication and authorization, &

- Interoperability: designing proper APIs

- AI Integration: The system must include integration with an AI-based service. The AI functionality must be accessed via a well-defined API and integrated into at least one backend microservice as part of a real system workflow.

There must be a minimum of 5 microservices – this means 5 separate backend applications. API Gateway does not count unless it contains business logic.

The project must also include a frontend client (does not count as a microservice).

Important notes: Services like databases, message brokers, frontend clients, 3rd-party services, etc. do not count as microservices.

For this project you are free to choose any development stack – programming languages, frameworks, cloud providers, database servers, etc.

You must write the specifications for your project including the functional and non-functional requirements.

From the very beginning of the project, you should set up a proper project management strategy (for the team collaboration) and use a version control system like git/GitHub and set up a CI/CD pipelines (for example using GitHub Actions) with automated tests. Part of the delivery will be a link to the monorepo hosting all the microservices where contributions from each student will be clearly visible via git history.


## Environments:

- Development environment should be configured using docker-compose.

- Production environment should be simulated using local Kubernetes cluster. It is not required to make a full cloud deployment, but it should be described in the report – especially how it will support properties like elasticity, high availability, and low latency.

- Stock brokerage web app

- Logistics & Package Tracking System

- Hospital & Patient Record System

- Hotel booking & room allocation system

- Payroll & employee management system

- Restaurant ordering & kitchen workflow system

- Warehouse & inventory optimization system

- Ride-sharing & trip tracking system

- Social media content & moderation backend

- Real-estate listing & rental system

- Food delivery & courier dispatch system

- Manufacturing production tracking system

- Banking system

- Movie rental / streaming system

- Car rental system

- Web shop

- Airline reservation system

The project must be complex enough to cover the curriculum of the whole course. Students are responsible for selecting a suitable project.

## Examples of suitable business ideas for the project:


## Architecture, Communication, and Quality Requirements

The following requirements must be implemented in the project.

The course will cover the theoretical background and motivation for each requirement.

## 1. Communication and Integration

- Backend microservices must primarily communicate with each other asynchronously using message queues.

- The backend must expose APIs to the frontend using both REST and GraphQL:

- o At least one microservice must expose a REST API.

- o At least one microservice must expose a GraphQL API. (Both approaches may be implemented in the same microservice or in separate microservices.)

- The system must demonstrate API interoperability between frontend and backend services, so that different API styles exposed by the backend work together seamlessly when consumed by the frontend as part of real system workflows.

- The frontend application must consume backend APIs using both REST and GraphQL and interact with multiple backend microservices.

- The project must include authentication and authorization, including:

- o Token-based authentication (e.g. JWT).

- o Integration with at least one third-party identity provider using OAuth/OpenID Connect (e.g. Google, Microsoft, GitHub, Facebook).

- The system must integrate with an AI-based service via an API, used by at least one backend microservice as part of its business logic.

## 2. System Architecture and Design Patterns

- Each backend microservice must be independently deployable.

- Each microservice must own its data and must not directly access another microservice’s database.

- The project must demonstrate the use of advanced distributed systems patterns, including:

- CQRS (Command Query Responsibility Segregation) where appropriate  the implementation needs to be justified.

- Immutable data handling, using:

- o Tombstone pattern and/or

- o Snapshot pattern.

- Idempotent operations, ensuring that repeated client requests do not cause duplicate side effects.

- Commutative message handlers, where message order does not affect the final system state.

- Saga pattern to ensure consistency across multiple services or databases using compensating actions rather than distributed transactions.


## 3. Cloud-Native and Operational Concerns

- The system must include a logging and monitoring solution suitable for distributed systems.

- The project must demonstrate cloud-native design principles, including:

- o Containerized services.

- o Clear deployment architecture.

- o The CI/CD pipeline must include automated execution of unit, integration, and system-level cooperation tests.

- The AI service may be simulated locally using a containerized local LLM (for example using tools such as Ollama), in order to avoid dependency on paid external APIs.

- Serverless functions must be used for at least one isolated task (e.g. image processing, background jobs, notifications). It can be simulated in local Kubernetes deployment (for example, a containerized console application scaled using KEDA as a ScaledJob).

## 4. Versioning Strategy

A consistent versioning strategy must be implemented and documented:

- Source code versioning using Git and GitHub or other platforms.

- Database versioning using database migration tools.

API versioning for REST APIs (GraphQL does not require versioning by design.)

## 5. Testing Requirements

- The project must include automated testing demonstrating correct behavior of individual services and their cooperation within the distributed system.

- The project must include basic security testing validating authentication and authorization behavior.

- Static analysis or static testing tools must be used to automatically detect code quality or security issues and must be executed as part of the CI/CD pipeline.

- Each backend microservice must include automated unit tests covering core business logic.

- The project must include automated integration tests validating at least one of each of the following:

- REST or GraphQL API behavior

- Asynchronous communication via message queues

- Database integration

- The project must include at least one system-level cooperation test demonstrating correct interaction between two or more backend services.

- The test must start one or more dependent services (for example using containers).

- The test must verify that the services cooperate as intended (typically via messaging).

- The test must focus on service interaction, not on UI or end-to-end user workflows.

- All tests must be executed automatically as part of the CI/CD pipeline.

- Test failures must cause the CI/CD pipeline to fail.


## 6. Documentation Requirements

Comprehensive documentation is mandatory and must include:

- GitHub README for the whole monorepo and for each microservice, and, if relevant, GitHub Wiki.

- Swagger / OpenAPI documentation for all REST APIs.

- GraphQL schema and endpoint documentation.

- AsyncAPI documentation (or an equivalent standard) for all message-based communication, including:

- o Message brokers, queues, or topics

- o Message schemas and payload formats

- o Producers and consumers

- o Event flows between microservices

- Overall system documentation, including:

- o Architecture diagrams

- o Description of communication between services

- o Explanation of applied patterns and design decisions

## Final Project Delivery to WISEflow

## Final Project Artifacts (things to deliver)

- Report

- Links to a GitHub monorepo containing all the source code

- o Documentation:

- GitHub readmes, wiki

- swagger for REST APIs

- A brief installation procedure that describes how to organize the codebase and initialize the databases in a development environment with full operational capabilities, for example to support onboarding a new group member and setting up a local development and Kubernetes environment.

## Final Project Report

Official requirements for the report can be found in the course catalog:

[https://katalog.kea.dk/course/9942251/2025-2026](https://katalog.kea.dk/course/9942251/2025-2026)

## The report should have this structure:

Cover page, including Title, Full names of all students in the group, Date of delivery List of figures

List of appendices Table of contents (paginated index)

## 1. Introduction

- 1.1. Problem description


- 1.2. Functional and non-functional requirements

- 1.3. Explanation of choices for the technology stack (databases, programming languages, frameworks, etc.).

- 2. System architecture

- 2.1. Introduction to the microservices architecture + architectural diagram of the whole system

- 2.2. Microservices Description

- For each backend microservice, the following must be explained. Diagrams and API specifications should be referenced rather than duplicated. Implementation details and code-level explanations should be avoided.

- Purpose and responsibilities, including why the functionality was isolated into this microservice

- Exposed APIs (REST / GraphQL / asynchronous), including rationale for the chosen communication style

- Consumed and produced events, and the role of this microservice in asynchronous workflows

- Data storage and ownership, including justification for database choice and schema boundaries

- Key business logic, focusing on behavior rather than implementation details

- Scalability and failure considerations, including how the service behaves under load or failure

- 2.3. Communication between microservices

- 2.4. Description of the patterns and techniques used in the project.

- Only patterns that are implemented must be described in detail; other relevant patterns may be discussed conceptually but only if justified.

- 2.4.1. CQRS

- 2.4.2. Immutable data (Tombstone pattern and Snapshot pattern)

- 2.4.3. Idempotence

- 2.4.4. Commutative message handlers

- 2.4.5. Saga pattern

- 2.4.6. Caching

- 2.4.7. …

- 3. Environments description

- 3.1. Development environment with docker-compose including the architecture diagram

- 3.2. Local Kubernetes deployment simulating a production-like environment including the architecture diagram:

- 3.2.1. Description of used technologies

- 3.2.2. CI/CD pipeline description and diagram

- 3.2.3. Monitoring and logging of the deployed system

- 3.2.4. Autoscaling configuration

- 3.3. Cloud deployment plan – deployment diagram, selected cloud providers and services, pricing strategies, scalability and availability considerations (both app and database


level), security and data management strategies, deployment and release approaches, and justified architectural trade-offs. Actual cloud deployment is not required.

## 4. Testing

- 4.1. Testing strategy and scope

- 4.2. Unit testing

- 4.3. Integration testing

- 4.4. System-level cooperation testing

- 4.5. Security testing focusing on verifying correct authentication and authorization

- 4.6. Static analysis and static testing to identify code quality or security issues.

- 4.7. Test execution in CI/CD

- 4.8. Limitations, risks, and trade-offs

## 5. Project management and team collaboration

- 5.1. Introduction to the project management and team collaboration

- 5.2. Description of the methods used during the project.

- 5.3. Versioning strategies for the source code, databases, and APIs

- 5.4. Documentation strategy

## 6. Discussion

- 6.1. Advantages and challenges of the distributed systems (application and database perspectives)

- 6.2. Pros and cons of used patterns like CQRS etc.

- 6.3. Scalability / Autoscaling (application, messaging, and database perspectives)

- 6.4. Possible improvements

- 7. Reflection: What was difficult about: designing the distributed system, project management, what design mistakes were made and fixed, what would be done differently next time, what you learned about databases in practice.

- 8. Conclusion: short high-level summary of the project, results, main insights.

- 9. References: All major design choices, technical claims, and comparisons must be supported by references. Sources must be cited in the text and listed in the References section. References should primarily be official documentation, textbooks, or academic or technical publications. Web tutorials and blogs may be used sparingly but should not be the primary sources. Use standard formats like APA, IEEE, or Harvard.

## 10. Appendix

---

# Implementation notes for the report (updated 2026-11-20)

These notes are for the team: they connect the course requirements to the code that now exists and record
the decisions that are worth arguing in the report. The task-level checklist is
[`docs/final-project-delivery.md`](final-project-delivery.md); the contracts stay in
[`contracts/`](../contracts).

## 1. Requirement → implementation in one line

| Requirement | Where it lives | What to show |
|---|---|---|
| ≥ 5 independently deployable services | `services/` (7) + `functions/email-function` | one `pom.xml` and Dockerfile per service, one database each |
| REST and GraphQL for the frontend | REST on all services, GraphQL on scheduling-query | `frontend/src/api` vs `frontend/src/graphql`; `contracts/openapi` vs `contracts/graphql` |
| Mostly asynchronous communication | RabbitMQ topic exchange, outbox in every producer | `contracts/asyncapi.yaml`, `docs/measurements/cqrs-read-model.md` |
| JWT + OAuth/OIDC | identity-service issues RS256; each service validates with `JWT_JWKS_URL`; Google, GitHub and local Keycloak | `services/identity-service`, §3 below |
| AI through an API in a workflow | scheduling → `scheduling.shift.understaffed.v1` → ai-service → Ollama | `services/ai-service`, `SchedulingAiIT` |
| CQRS | scheduling and leave, `APP_ROLE=command|query` | Compose services `*-command`/`*-query`, `CqrsReadModelBenchmarkIT` |
| Immutable data | tombstones (`*.deleted.v1`), snapshot payloads, leave ledger | `contracts/events/payloads/tombstone.schema.json`, `LeaveLedgerIT` |
| Idempotence | `Idempotency-Key` on POST; `processed_event` per consumer; unique `event_id` in audit | `IdempotencyServiceTest`, `NotificationServiceIT`, `AuditServiceIT` |
| Commutative handlers | version-based upsert, shuffled-order tests | `*ShuffledOrderTest`, `LeaveProjectionReplayIT` |
| Saga with compensation | leave-command orchestrates, scheduling releases or rejects, ledger reversal | `LeaveApprovalSagaIT` |
| Serverless | email-function on `email.send`, KEDA ScaledJob | `EmailFunctionIT`, `kubernetes/autoscaling/keda.yaml` |
| Tests and CI | Surefire/Failsafe/Testcontainers, cooperation tests, CodeQL, Trivy | `.github/workflows`, `docs/ci-demo.md` |
| Docs | per-service READMEs, springdoc contract UI, AsyncAPI artifact, wiki | `services/*/README.md`, `docs/wiki/` |

## 2. Three services that were skeletons are now real

- **notification-service** consumes `notification.events` (14 routing keys), keeps its own copy of employee
  e-mail/role/active built from `workforce.employee.*`, stores one in-app notification per event and
  recipient, and writes `notification.email.requested.v1` to its outbox in the same transaction. The REST
  read is caller-scoped: an employee can read and acknowledge only their own notifications (the ownership
  check is the 403 path of `PATCH`).
- **audit-service** appends every catalogued business event unchanged into `audit_entry` with a unique
  `event_id` (idempotent) and exposes `GET /v1/auditlogs` to Administrators only, filterable by event type,
  aggregate, correlation id and time. It is append-only: there is no update or delete path.
- **email-function** drains `email.send` over real SMTP (Mailpit locally), acks only after SMTP accepted the
  message, dead-letters after three attempts, and exits when idle so KEDA can scale it to zero. Its dedupe is
  in memory; the report should present that as the trade-off of a stateless function (see limitations).

## 3. OAuth/OIDC: Google, GitHub and a local provider

The course requires a third-party identity provider. The system has three:

1. **Google** (`OidcProvider.GOOGLE`) — authorization code + PKCE (S256), ID token verified against Google's
   JWKS, issuer, audience and expiry, `email_verified` required.
2. **GitHub** (`OidcProvider.GITHUB`) — OAuth 2.0 only; the verified primary e-mail is read from
   `/user/emails`.
3. **Local Keycloak** (`OidcProvider.LOCAL`) — a real OpenID Connect provider with discovery endpoints, a
   login form, a client with PKCE S256 and the same id-token validation. It exists so the flow can be
   demonstrated and tested without external accounts or secrets.

All three end in the same place: the verified e-mail must match an **existing active account** (accounts come
only from `workforce.employee.*`), identity stores a link `(provider, subject) -> employeeId`, and the
callback redirects to the frontend with `#token=<JWT>`. The token is in the fragment, so it never reaches a
server log or a `Referer` header. Unknown or inactive e-mails are refused with the same error code.

Local demo (Compose):

```bash
docker compose -f docker-compose.microservices.yml --profile app up -d --build
scripts/seed-dev.sh
# https://localhost:8443/login -> "Local demo (Keycloak)"
# Keycloak users (email / Demo1234!):
#   admin@shift-happens.example       (Administrator)
#   sofie.jensen@shift-happens.example (Manager)
#   ida.hansen@shift-happens.example  (Employee)
# Keycloak admin console: http://localhost:8090  (admin / admin)
```

Why a local provider does not weaken the requirement: the protocol, the client registration, PKCE, the
id-token signature check and the e-mail matching are identical to Google's. The only difference is where the
user types the password. In the report, present the local provider as the **reproducible development
identity provider** and Google/GitHub as the production configuration (`OIDC_GOOGLE_*`, `OIDC_GITHUB_*`).


## 4. Decisions worth defending in the discussion chapter

| Decision | Why | Cost / alternative |
|---|---|---|
| One queue per consumer, routing key = `eventType` | Fan-out without a broker-side registry; each consumer owns its retry/DLQ policy | More queues and bindings than one shared queue; documented in AsyncAPI |
| Transactional outbox in every producer | A state change can never commit without its event, even if RabbitMQ is down | A relay adds a small latency (2 s default) and a table per service |
| CQRS only for scheduling and leave | Their reads (nested schedules, balances) are much heavier than their writes and have different scaling needs | Two deployments and a read model to rebuild; justified with measurements |
| Version-based upsert in every replica | Event order is not guaranteed; a late snapshot must not overwrite a newer one | Every consumer stores `aggregateVersion` |
| Tombstone kept by the consumer | A delete cannot be undone by a late update | Rows stay; queries filter `deleted` |
| Audit consumes **all** business events, no email events | Complete traceability without personal data from e-mail payloads | Larger log; a retention policy is future work |
| In-memory dedupe in email-function | Stateless function, no database, exactly the serverless constraint | A restart can resend; documented limitation and improvement |
| Keycloak as local OIDC provider | Reproducible demo, real protocol, no secrets in the repo | One more container; production uses Google/GitHub |
| Hand-written OIDC client instead of `oauth2Login` | The service is stateless and the contract fixes the URL shape | More code to own; covered by `OidcLoginServiceTest` and `OidcIT` |

## 5. Numbers to quote (from the repository, not from memory)

- `docs/measurements/cqrs-read-model.md`: the read-model query plan and latency, with and without the
  batched GraphQL resolvers.
- `kubernetes/ollama/latency.md`: the local model latency used for the AI fallback budget.
- Surefire/Failsafe counts in the CI logs: identity 78 unit + 34 IT, leave 94 unit + 196 IT, notification
  8 + 5, audit 1 + 2, email-function 2 IT, plus the cooperation tests.
- `docker-compose.microservices.yml`: seven services, nine databases (Postgres/Mongo), RabbitMQ, Mailpit,
  OTel collector, Keycloak, Caddy gateway.

## 6. Limitations the report should own

- Email dedupe is process-local; SMTP is not transactional.
- Manager alerts for understaffed shifts fan out to every active manager (no department-manager relation in
  the employee snapshot; a synchronous lookup is forbidden).
- The frontend has no automated unit tests; the backend carries the test strategy.
- Grafana/Loki/Tempo and KEDA are prepared but not installed in the Docker Desktop cluster (P7.1/P7.4).
- SonarQube Cloud is configured but skipped until `SONAR_TOKEN` exists.

## 7. Suggested division of the remaining report work

| Section | Source | Who |
|---|---|---|
| 1 Introduction, requirements, stack | `docs/architecture.md` §1, §3 | two people |
| 2.1–2.3 Architecture, services, communication | `docs/architecture.md` §4, `services/*/README.md`, `contracts/README.md` | service owners |
| 2.4 Patterns | `docs/architecture.md` §4.3, ADRs, this document §4 | one person |
| 3 Environments | Compose, `kubernetes/`, `.github/workflows`, `docs/ci-demo.md`, cloud plan | one person |
| 4 Testing | test classes, CI runs, `docs/final-project-delivery.md` §2 | one person |
| 5 Project management and versioning | `docs/project-plan.md`, git history, ADRs | one person |
| 6–8 Discussion, reflection, conclusion | this document §4–6 + retro notes | all, one editor |
| 9 References | official docs: Spring Boot, RabbitMQ, Keycloak, Kubernetes, OAuth 2.0 RFC 6749, PKCE RFC 7636, CQRS/event-sourcing literature | all |

