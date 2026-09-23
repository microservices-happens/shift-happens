# ai-service

**Purpose:** suggest who should cover a shift that has become understaffed. The AI is used inside a real workflow: leave approval releases an assignment → the shift is short → a ranked suggestion is shown to the manager.
**Database:** `ai-db` (Postgres, Flyway): processed `eventId`s, suggestions, and the prompt/response log for review. **Compose:** `ai-service` (+ `ollama` with `--profile ai`).

## AI integration
- **Model API:** Ollama's HTTP API (`LLM_BASE_URL`, `LLM_MODEL`, default `llama3.2:1b`), running locally in a container, so there is no paid API or data leaving the machine. Any OpenAI-compatible endpoint works through the same client.
- **Input:** the `scheduling.shift.understaffed.v1` payload. It already contains only qualified, available candidates, so ai-service needs no access to other services' data.
- **Prompt:** asks for a JSON ranking with a one-line reason per candidate. The response is validated, and only candidate ids from the input are accepted.
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
