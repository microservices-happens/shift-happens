import { ApiError, type Problem } from './types';

// The gateway exposes every service's `/v1/<resource>` as `/api/v1/<resource>`
// (contracts/README.md, "REST conventions"). Paths passed to apiRequest are relative to /v1.
const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

let tokenGetter: () => string | null = () => null;
let unauthorizedHandler: () => void = () => {};

export function configureApiClient(opts: {
  getToken: () => string | null;
  onUnauthorized: () => void;
}) {
  tokenGetter = opts.getToken;
  unauthorizedHandler = opts.onUnauthorized;
}

/**
 * The current bearer token and the 401 handler, shared with the GraphQL client (src/graphql) so both
 * transports use the same session. Never log the token.
 */
export function currentToken(): string | null {
  return tokenGetter();
}

export function reportUnauthorized(): void {
  unauthorizedHandler();
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  skipAuth?: boolean;
  /**
   * Every POST carries an `Idempotency-Key` (contracts/README.md). Pass the key when the caller
   * retries the same user action; otherwise a fresh one is generated per call.
   */
  idempotencyKey?: string;
}

/** Fills `{name}` placeholders of a contract path, e.g. `pathWithParams('/employees/{id}', { id: 4 })`. */
export function pathWithParams(
  path: string,
  params: Record<string, string | number>
): string {
  return path.replace(/\{(\w+)\}/g, (_, name: string) => {
    const value = params[name];
    if (value === undefined) throw new Error(`Missing path parameter "${name}" for ${path}`);
    return encodeURIComponent(String(value));
  });
}

function isProblem(payload: unknown): payload is Partial<Problem> {
  return typeof payload === 'object' && payload !== null && ('detail' in payload || 'title' in payload);
}

function errorMessage(payload: unknown, status: number): string {
  if (isProblem(payload)) {
    return payload.detail || payload.title || `Request failed: ${status}`;
  }
  if (payload && typeof payload === 'object' && 'error' in payload) {
    return String((payload as { error: unknown }).error);
  }
  return `Request failed: ${status}`;
}

export async function apiRequest<T>(
  path: string,
  { method = 'GET', body, skipAuth = false, idempotencyKey }: RequestOptions = {}
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  if (method === 'POST') {
    headers['Idempotency-Key'] = idempotencyKey ?? crypto.randomUUID();
  }

  if (!skipAuth) {
    const token = tokenGetter();
    if (token) headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (response.status === 401 && !skipAuth) {
    unauthorizedHandler();
  }

  // Matches application/json and application/problem+json (RFC 9457 errors).
  const contentType = response.headers.get('content-type') ?? '';
  const payload = /[/+]json\b/.test(contentType)
    ? await response.json().catch(() => null)
    : await response.text().catch(() => null);

  if (!response.ok) {
    throw new ApiError(response.status, payload, errorMessage(payload, response.status));
  }

  return payload as T;
}
