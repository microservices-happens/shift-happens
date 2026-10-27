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

Run the tests from the repository root with
`./mvnw -f services/ai-service/pom.xml test` (on Windows: `./mvnw.cmd`).
The service still needs Scheduling's real understaffed producer and query projection
before suggestions appear in the frontend.

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
**Database:** `ai-db` (Postgres, Flyway): processed `eventId`s, suggestions, and the prompt/response log for review. **Compose:** `ai-service` (+ `ollama` with `--profile ai`).

## AI integration
- **Model API:** Ollama's HTTP API (`LLM_BASE_URL`, `LLM_MODEL`, default `llama3.2:1b`), running locally in a container, so there is no paid API or data leaving the machine. Any OpenAI-compatible endpoint works through the same client.
- **Input:** the `scheduling.shift.understaffed.v1` payload. It already contains only qualified, available candidates, so ai-service needs no access to other services' data.
- **Prompt:** asks for a JSON ranking with one overall reason (at most 100 words). The response is validated, and only candidate ids from the input are accepted.
- **Fallback:** on timeout (`LLM_TIMEOUT`), an invalid response or the model being down, it ranks by fewest hours this week and sets `source: rule`. The workflow never blocks on the AI.
- **Advisory only:** it never assigns anyone. A manager confirms through Scheduling's REST API.

## Consumes
| Queue | Routing key |
|---|---|
| `ai.suggestions` | `scheduling.shift.understaffed.v1` |

Idempotent by `eventId`: a redelivery does not call the LLM again.

## Publishes (outbox)
`ai.replacement.suggested.v1` → scheduling-query (GraphQL `Shift.replacementSuggestions`), notification-service and audit-service.

## Provides
Only `GET /health`. The suggestions reach the frontend through Scheduling's GraphQL API.

## Done when
- [ ] Unit test: an invalid or hallucinated LLM output is rejected and the rule fallback is used.
- [ ] Integration test with a stubbed LLM endpoint: an understaffed event in → a suggestion event out.
