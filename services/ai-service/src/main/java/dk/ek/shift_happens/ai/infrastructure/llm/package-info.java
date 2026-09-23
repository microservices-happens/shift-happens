/**
 * Outbound adapter to the LLM (Ollama HTTP API via Spring AI). Validates the model's JSON output
 * and falls back to rule-based ranking on timeout or invalid output.
 */
package dk.ek.shift_happens.ai.infrastructure.llm;
