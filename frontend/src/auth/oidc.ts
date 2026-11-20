import type { AuthUser, RoleName } from '../api/types';

/**
 * Google/GitHub login (P5.3) against identity-service's authorization-code + PKCE flow
 * (contracts/openapi/identity.yaml, services/identity-service/README.md):
 *
 * 1. The browser navigates to /api/v1/auth/oidc/{provider}/authorize, which answers 302 to the provider.
 * 2. The provider sends the browser to the backend callback, which answers 302 to
 *    /login/callback#token=<jwt> or /login/callback#error=<code>.
 *
 * The token arrives in the URL fragment, so it never reaches a server or a Referer header.
 * Never log it.
 */

export type OidcProvider = 'google' | 'github';

export const OIDC_PROVIDERS: { id: OidcProvider; label: string }[] = [
  { id: 'google', label: 'Google' },
  { id: 'github', label: 'GitHub' },
];

export function providerLabel(provider: OidcProvider | null | undefined): string {
  return OIDC_PROVIDERS.find((p) => p.id === provider)?.label ?? 'the login provider';
}

/**
 * The gateway serves identity-service under /api/v1. VITE_API_BASE_URL is /api in dev and /api/v1 in
 * the image, so drop a trailing /v1 before adding the versioned path.
 */
function oidcBaseUrl(): string {
  const apiBase = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '').replace(/\/v1$/, '');
  return `${apiBase}/v1/auth/oidc`;
}

export function authorizeUrl(provider: OidcProvider): string {
  return `${oidcBaseUrl()}/${provider}/authorize`;
}

// ---- Pending login marker -------------------------------------------------
// Set in this tab right before leaving for the provider. /login/callback only accepts a token when
// it is present, so a link to /login/callback#token=<someone else's jwt> cannot log a user in
// (login CSRF). It holds no secret: only the provider, where to go afterwards and a timestamp.

const PENDING_KEY = 'shift_happens_oidc_pending';
/** identity-service keeps the state for 10 minutes; allow a little slack. */
const PENDING_TTL_MS = 15 * 60 * 1000;

interface PendingLogin {
  provider: OidcProvider;
  from: string;
  startedAt: number;
}

export function rememberPendingLogin(provider: OidcProvider, from: string): void {
  try {
    const pending: PendingLogin = { provider, from, startedAt: Date.now() };
    sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending));
  } catch {
    // storage blocked: the callback will report that the login was not started here
  }
}

/** Reads and removes the marker, so it works once. */
export function takePendingLogin(): PendingLogin | null {
  try {
    const raw = sessionStorage.getItem(PENDING_KEY);
    sessionStorage.removeItem(PENDING_KEY);
    if (!raw) return null;
    const pending = JSON.parse(raw) as Partial<PendingLogin>;
    if (
      (pending.provider !== 'google' && pending.provider !== 'github') ||
      typeof pending.startedAt !== 'number' ||
      Date.now() - pending.startedAt > PENDING_TTL_MS
    ) {
      return null;
    }
    const from = typeof pending.from === 'string' && pending.from.startsWith('/') ? pending.from : '/';
    return { provider: pending.provider, from, startedAt: pending.startedAt };
  } catch {
    return null;
  }
}

// ---- Callback fragment ----------------------------------------------------

export type CallbackResult = { token: string } | { error: string };

/** Parses `#token=<jwt>` or `#error=<code>` as sent by OidcController. */
export function parseCallbackFragment(hash: string): CallbackResult {
  const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash);
  const token = params.get('token');
  if (token) return { token };
  return { error: params.get('error') || 'missing_result' };
}

/**
 * Error codes from OidcLoginException (+ server_error from OidcController) and the frontend's own
 * checks. `provider` is used for wording only.
 */
export function callbackErrorMessage(code: string, provider: OidcProvider | null): string {
  const name = providerLabel(provider);
  switch (code) {
    case 'unknown_provider':
      return `Sign-in with ${name} is not enabled on this server.`;
    case 'invalid_state':
      return 'This sign-in attempt is not valid or was already used. Please start again.';
    case 'expired_state':
      return 'The sign-in took too long. Please start again.';
    case 'access_denied':
      return `Sign-in was cancelled at ${name}.`;
    case 'invalid_request':
      return `${name} did not return a sign-in code. Please try again.`;
    case 'provider_error':
      return `${name} could not confirm who you are. Please try again later.`;
    case 'email_not_verified':
      return `Your ${name} email address is not verified. Verify it with ${name} and try again.`;
    case 'no_active_account':
      return `No active Shift Happens account matches your ${name} email. Ask an administrator to check the email on your employee record.`;
    case 'server_error':
      return 'Something went wrong on the server. Please try again.';
    case 'not_started':
      return 'This sign-in was not started from this browser tab, or it took too long. Please start again.';
    case 'invalid_token':
      return 'The server returned a sign-in token that could not be read. Please try again.';
    default:
      return 'Sign-in failed. Please try again.';
  }
}

/** Shown when /authorize itself fails (400 problem+json for a disabled provider). */
export function authorizeErrorMessage(provider: OidcProvider, status: number | null): string {
  if (status === null) return 'Could not reach the server. Is the backend running?';
  if (status === 400) return callbackErrorMessage('unknown_provider', provider);
  return `Could not start sign-in with ${providerLabel(provider)} (status ${status}). Please try again.`;
}

/**
 * Asks /authorize first without following the redirect, so a disabled provider (400 problem+json,
 * see OidcDisabledProviderIT) shows a message here instead of a raw JSON page. `ok` when /authorize
 * redirects; otherwise the HTTP status, or `status: null` for a network failure.
 *
 * Cost: this probe makes identity-service store one extra login attempt, which is never used and
 * expires after 10 minutes like any abandoned attempt.
 */
export async function checkProviderAvailable(provider: OidcProvider): Promise<{ ok: true } | { ok: false; status: number | null }> {
  try {
    const response = await fetch(authorizeUrl(provider), { redirect: 'manual', credentials: 'omit' });
    if (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400)) {
      return { ok: true };
    }
    return { ok: false, status: response.status };
  } catch {
    return { ok: false, status: null };
  }
}

// ---- Token claims ---------------------------------------------------------

const ROLE_NAMES: readonly RoleName[] = ['Administrator', 'Manager', 'Employee'];

function isRoleName(value: unknown): value is RoleName {
  return typeof value === 'string' && (ROLE_NAMES as readonly string[]).includes(value);
}

interface ShiftHappensClaims {
  employeeId: number;
  role: RoleName;
  exp?: number;
}

function decodeBase64Url(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Reads the claims the JWT carries (sub, employeeId, role, iss, iat, exp; see JwtTokenIssuer). This
 * does NOT verify the signature: the services do that on every request. It only rejects something
 * that is not a usable Shift Happens token, so a broken fragment does not become a broken session.
 */
export function readTokenClaims(token: string, nowMs: number = Date.now()): ShiftHappensClaims | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const payload = JSON.parse(decodeBase64Url(parts[1])) as Record<string, unknown>;
    const employeeId = Number(payload.employeeId ?? payload.sub);
    const role = payload.role;
    const exp = payload.exp;
    if (!Number.isInteger(employeeId) || !isRoleName(role)) return null;
    if (typeof exp === 'number' && exp * 1000 <= nowMs) return null;
    return { employeeId, role, exp: typeof exp === 'number' ? exp : undefined };
  } catch {
    return null;
  }
}

/**
 * The OIDC callback only hands over the JWT, which carries the employee id and role but not the
 * name or email that local login's LoginResponse has. Those start empty and are filled from
 * workforce afterwards (AuthContext).
 */
export function userFromClaims(claims: ShiftHappensClaims): AuthUser {
  return {
    employeeId: claims.employeeId,
    roleName: claims.role,
    employeeNumber: '',
    firstName: '',
    lastName: '',
    email: '',
  };
}
