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

Run the unit tests (`RankingTest`, `OllamaClientTest`, `SecurityRulesTest`, `SuggestionServiceRetryTest`) from the repository root with
`./mvnw -B -f services/ai-service/pom.xml test` (on Windows: `./mvnw.cmd`); `verify` adds
`AiServiceApplicationIT` and `AiDeadLetterIT` (Docker required). Scheduling now publishes the understaffed event and its query
role stores the suggestion (`Shift.replacementSuggestions`), and the frontend
shows them on understaffed shifts through GraphQL (#104, `UnderstaffedShiftsPanel.tsx`; not verified end to end).

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
The binding is the exact key. **Three attempts, then dead-letter** (as the other services): a message that
fails (malformed JSON, wrong `eventType`, an invalid payload, a database error) is tried three times, 1 s and
then 2 s apart, and then republished to `shift-happens.dlx` with the queue name `ai.suggestions` as routing key.
It lands in `ai.suggestions.dlq`, which is bound with exactly that key, so it holds only ai's failures. It is
never requeued forever. Every attempt logs `Received <eventType> event <eventId>`.

- **A model failure is not a delivery failure.** A timeout (`LLM_TIMEOUT`), an HTTP error, the model being
  down or an invalid answer is answered by the rule-based fallback (`source: rule`) on the first attempt; it
  never causes a retry.
- **Bounded processing time.** The model is called at most once per message: the outcome is kept in the retry
  context (`ModelCallMemory`) and a retry caused by something after the call (e.g. the database) reuses it.
  One message therefore takes at most one `LLM_TIMEOUT` plus 3 s of back-off plus three short database
  attempts, not three times `LLM_TIMEOUT`. Payload checks run before the model call, so an unusable event
  fails fast.
- Covered by `AiDeadLetterIT` (three attempts, own DLQ only, one model call across a database failure, model
  failure handled without a retry) and `SuggestionServiceRetryTest`.
- **Upgrading an existing broker needs no manual step.** Older versions bound `ai.suggestions.dlq` to the DLX
  with `#` (it received every service's dead letters). `StaleDeadLetterBindingRemover` deletes that binding on
  every broker connection, after the topology is declared. The queue arguments are deliberately unchanged
  (only `x-dead-letter-exchange`, no `x-dead-letter-routing-key`), because RabbitMQ refuses to redeclare an
  existing queue with different arguments; the routing key comes from the retry recoverer. Messages waiting in
  `ai.suggestions` are kept; messages already in `ai.suggestions.dlq`, including foreign copies from the old
  `#` binding, are kept too: inspect and purge them (docs/wiki/Troubleshooting.md).

## Publishes (outbox)
`ai.replacement.suggested.v1` → scheduling-query (GraphQL `Shift.replacementSuggestions`), notification-service and audit-service.

## Provides
Only `GET /health`. The suggestions reach the frontend through Scheduling's GraphQL API.

## Correlation id (P1.6)

`config/CorrelationIdFilter` takes `X-Correlation-Id` from the request (a token of up to 128
`[A-Za-z0-9._:-]` characters) or generates a UUID, puts it in the MDC as `correlationId`, and echoes
it on the response. Every log line prints it (`logging.pattern.correlation`). The understaffed event's `correlationId` is in the MDC while it is handled and is copied into `ai.replacement.suggested.v1`.

Security (P1.7): `GET /health` is public; any other path needs a Bearer JWT from identity-service, validated
against the JWKS at `JWT_JWKS_URL` (cached) with `iss` and `exp` checked. The service has no other endpoint
today, so the general rules of `contracts/README.md` apply to whatever is added: `GET` needs any valid token,
writes need `Administrator` or `Manager`. `SecurityRulesTest` covers this without containers.

## Done when
- [x] Tests cover rejected candidate IDs and fallback for malformed model output.
- [x] Unit test verifies that the Ollama client times out when the HTTP endpoint stalls.
- [x] Integration test with PostgreSQL and RabbitMQ in Testcontainers and a stubbed LLM HTTP endpoint.
  `mvn verify` checks that an understaffed event produces a suggestion, including fallback for malformed model output.
