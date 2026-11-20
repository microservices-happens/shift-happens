import { useState } from 'react';
import { ApiError } from './types';

/**
 * One `Idempotency-Key` per user action (contracts/README.md, "Idempotency"): the server replays the
 * stored first response for 24 h when the same key comes again, and answers 409 when the same key
 * comes with a different body.
 *
 * An `IdempotentAction` lives as long as the user action it belongs to (e.g. one open form, see
 * `useIdempotentAction`). It hands out one key per distinct request body:
 * - a retry of the same body (manual "Save" again, or the automatic retry below) reuses the key, so a
 *   POST whose response was lost is replayed by the server instead of creating a duplicate;
 * - a changed body gets its own key (the same key with a different body would be a 409); changing
 *   the body back reuses the earlier key, because that first request may have been stored;
 * - after a success the key of that body is dropped, so a deliberate second submission is a new
 *   action.
 * The server does not store failed commands, so a retry after a 4xx runs the command again.
 */
export class IdempotentAction {
  private readonly keys = new Map<string, string>();

  /** The key for this body: the stored one when the same body was sent before, otherwise a new one. */
  keyFor(body: unknown): string {
    const fingerprint = stableStringify(body);
    let key = this.keys.get(fingerprint);
    if (!key) {
      key = crypto.randomUUID();
      this.keys.set(fingerprint, key);
    }
    return key;
  }

  /** Forget the key of a body that succeeded: sending it again is a new user action. */
  complete(body: unknown): void {
    this.keys.delete(stableStringify(body));
  }

  /** Forget every key, e.g. after the server reported a key conflict. */
  reset(): void {
    this.keys.clear();
  }

  /**
   * Sends one POST of this action with its key. A lost response (network error) or a gateway
   * error (502/503/504) is retried with the same key and body; every other error is returned to
   * the caller, which may retry by calling `run` again with the same body.
   */
  async run<T>(body: unknown, send: (idempotencyKey: string) => Promise<T>): Promise<T> {
    const key = this.keyFor(body);
    for (let attempt = 0; ; attempt++) {
      try {
        const result = await send(key);
        this.complete(body);
        return result;
      } catch (err) {
        if (isIdempotencyConflict(err)) {
          this.reset();
          throw new ApiError(err.status, err.body, IDEMPOTENCY_CONFLICT_MESSAGE);
        }
        if (attempt >= RETRY_DELAYS_MS.length || !isRetryable(err)) throw err;
        await delay(RETRY_DELAYS_MS[attempt]);
      }
    }
  }
}

/** One `IdempotentAction` per mounted component: a form keeps its keys while it is open. */
export function useIdempotentAction(): IdempotentAction {
  const [action] = useState(() => new IdempotentAction());
  return action;
}

/** Waits before the automatic retries of a POST whose response was lost. */
const RETRY_DELAYS_MS = [500, 1500];

const IDEMPOTENCY_CONFLICT_TYPE = '/problems/idempotency-conflict';

export const IDEMPOTENCY_CONFLICT_MESSAGE =
  'This action was already sent with different data, so it was not applied again. ' +
  'Reload the page to check whether the first attempt was saved before you try again.';

/**
 * The problem type the services use for "same key, different body" (ApiExceptionHandler in leave,
 * scheduling and workforce). A 409 business-rule violation has another type and keeps its message.
 */
export function isIdempotencyConflict(err: unknown): err is ApiError {
  if (!(err instanceof ApiError) || err.status !== 409) return false;
  const body = err.body;
  if (typeof body !== 'object' || body === null || !('type' in body)) return false;
  const type = (body as { type: unknown }).type;
  return typeof type === 'string' && type.endsWith(IDEMPOTENCY_CONFLICT_TYPE);
}

function isRetryable(err: unknown): boolean {
  // fetch rejects with a TypeError when the request or its response was lost on the network.
  if (err instanceof TypeError) return true;
  return err instanceof ApiError && (err.status === 502 || err.status === 503 || err.status === 504);
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/** JSON with sorted object keys, so two equal bodies give the same fingerprint. */
function stableStringify(value: unknown): string {
  return JSON.stringify(value, (_key, v: unknown) => {
    if (v === null || typeof v !== 'object' || Array.isArray(v)) return v;
    return Object.fromEntries(
      Object.entries(v as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    );
  });
}
