/**
 * Session storage keys and readers with no imports, so both the API client and the auth context can use
 * them without a cycle. The token must be readable at request time, including the very first effect after
 * a hard reload (children run their effects before the auth provider's).
 */
export const TOKEN_KEY = 'shift_happens_token';
export const USER_KEY = 'shift_happens_user';

export function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function readStoredUser<T>(): T | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}
