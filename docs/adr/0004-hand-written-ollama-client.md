# 0004. Hand-written Ollama client instead of Spring AI

- **Status:** Proposed
- **Date:** 2026-10-28

## Context
The AI Service ranks replacement candidates with a local LLM (Ollama) and must fall back to a rule-based ranking when the model is slow, down or answers with something invalid (`docs/architecture.md` §4.4). The architecture (§3) and the project plan (P4.1) named Spring AI as the way to call Ollama. The service was implemented in PR #23 without it.

Why Spring AI was dropped is not written down in the PR, the commits or the service README: **rationale not recorded**. The team must add it before this ADR is accepted.

## Decision
- `OllamaClient` (`services/ai-service/.../infrastructure/llm/`) calls Ollama's `POST /api/generate` directly with the JDK `java.net.http.HttpClient` and Jackson. `services/ai-service/pom.xml` has no Spring AI dependency.
- The request carries a JSON schema in Ollama's `format` field (a complete ranking, `employeeId` limited to the candidate ids from the event), `stream: false` and `temperature: 0`.
- `LLM_BASE_URL`, `LLM_MODEL` and `LLM_TIMEOUT` configure it. The timeout is set on the HTTP request.
- The model's answer is still validated in the service, and any failure leads to the rule-based fallback.

## Consequences
- **Easier:** no extra dependency. The model is replaced by a plain stub HTTP endpoint in tests: the unit test for a stalled request (PR #84) and the Testcontainers integration test (PR #83).
- **Harder:** the client only speaks Ollama's `/api/generate`. The claim that the same client works with a hosted or OpenAI-compatible model (`docs/architecture.md` §3 and §4.5, `services/ai-service/README.md`) no longer holds without a second client.
- `docs/architecture.md` §3, `docs/project-plan.md` P4.1 and `infrastructure/llm/package-info.java` still say "Spring AI" and must be corrected.

## Alternatives considered
- Spring AI (the original plan): why not is not recorded.
