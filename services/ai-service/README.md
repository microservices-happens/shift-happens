# ai-service

## Current implementation

The first vertical slice is implemented: a RabbitMQ listener consumes
`scheduling.shift.understaffed.v1`, calls Ollama's `/api/generate`, validates a complete
ranking of the supplied candidate IDs and one overall reason of at most 100 words, and falls back to fewest scheduled hours on
model failure. A database transaction records the input event ID, prompt/response and
`ai.replacement.suggested.v1` in an outbox. A scheduled relay publishes the outbox.
Redelivering the same input event does not create another suggestion. Prompt and
model-response logs are deleted after seven days by a daily cleanup job.
The Ollama call runs before the short database transaction that commits the processed
event ID, log and outbox row together.

Run the unit tests (`RankingTest`, `OllamaClientTest`, `SecurityRulesTest`) from the repository root with
`./mvnw -B -f services/ai-service/pom.xml test` (on Windows: `./mvnw.cmd`); `verify` adds
`AiServiceApplicationIT` (Docker required). Scheduling now publishes the understaffed event and its query
role stores the suggestion (`Shift.replacementSuggestions`), but the frontend does not read GraphQL yet
(P5.2), so suggestions do not appear in the UI.

### Try the real model locally (PowerShell)

From the repository root:

```powershell
docker compose -f docker-compose.microservices.yml --profile app --profile ai up -d --build ai-service ollama
docker compose -f docker-compose.microservices.yml --profile ai run --rm ollama-pull
.\scripts\ai-demo.ps1
```

The demo script publishes a synthetic `scheduling.shift.understaffed.v1` event,
waits for the corresponding suggestion, prints the source, overall reason and ranking,
then removes its temporary output queue. If RabbitMQ uses non-default development
credentials, pass `-RabbitUser` and `-RabbitPassword` to the script. A `source` of
`rule` means the model was unavailable or its answer failed validation; check
`docker compose -f docker-compose.microservices.yml logs ai-service` for the cause.

**Purpose:** suggest who should cover a shift that has become understaffed. The AI is used inside a real workflow: leave approval releases an assignment → the shift is short → a ranked suggestion is shown to the manager.
**Database:** `ai-db` (Postgres, Flyway `V1__init.sql`): `processed_event` (input `eventId`s), `suggestion_log` (prompt/response for review, deleted after seven days) and `outbox_event` (the published suggestions). **Compose:** `ai-service` (+ `ollama` with `--profile ai`).

## AI integration
- **Model API:** Ollama's HTTP API (`POST <LLM_BASE_URL>/api/generate` with a JSON-schema `format`, `LLM_MODEL`, default `llama3.2:1b`), running locally in a container, so there is no paid API or data leaving the machine. `OllamaClient` speaks only Ollama's `/api/generate`; an OpenAI-compatible endpoint is not supported (the Compose comment says otherwise).
- **Input:** the `scheduling.shift.understaffed.v1` payload. It already contains only qualified, available candidates, so ai-service needs no access to other services' data.
- **Prompt:** asks for a JSON ranking with one overall reason (at most 100 words). The response is validated, and only candidate ids from the input are accepted.
- **Fallback:** on timeout (`LLM_TIMEOUT`, default `PT20S` in `application.yml`, `PT90S` in Compose and Kubernetes), an invalid response or the model being down, it ranks by fewest hours this week and sets `source: rule`. The workflow never blocks on the AI.
- **Advisory only:** it never assigns anyone. A manager confirms through Scheduling's REST API.

## Consumes
| Queue | Routing key |
|---|---|
| `ai.suggestions` | `scheduling.shift.understaffed.v1` |

Idempotent by `eventId`: a redelivery does not call the LLM again.
The binding is the exact key; the queue dead-letters to `shift-happens.dlx`. There is no retry: the listener
container does not requeue, so a message that fails once (for example a malformed payload) is dead-lettered
straight away. `ai.suggestions.dlq` is bound to the DLX with `#`, so it also receives dead letters of other
services' queues.

## Publishes (outbox)
`ai.replacement.suggested.v1` → scheduling-query (GraphQL `Shift.replacementSuggestions`), notification-service and audit-service.

## Provides
Only `GET /health`. The suggestions reach the frontend through Scheduling's GraphQL API.

Security (P1.7): `GET /health` is public; any other path needs a Bearer JWT from identity-service, validated
against the JWKS at `JWT_JWKS_URL` (cached) with `iss` and `exp` checked. The service has no other endpoint
today, so the general rules of `contracts/README.md` apply to whatever is added: `GET` needs any valid token,
writes need `Administrator` or `Manager`. `SecurityRulesTest` covers this without containers.

## Done when
- [x] Tests cover rejected candidate IDs and fallback for malformed model output.
- [x] Unit test verifies that the Ollama client times out when the HTTP endpoint stalls.
- [x] Integration test with PostgreSQL and RabbitMQ in Testcontainers and a stubbed LLM HTTP endpoint.
  `mvn verify` checks that an understaffed event produces a suggestion, including fallback for malformed model output.
