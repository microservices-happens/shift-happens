import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiRequest, configureApiClient } from '../api/client';
import { getEmployee } from '../api/employees';
import type { AuthUser, LoginResponse } from '../api/types';
import { readTokenClaims, userFromClaims } from './oidc';

const TOKEN_KEY = 'shift_happens_token';
const USER_KEY = 'shift_happens_user';

interface AuthState {
  user: AuthUser | null;
  token: string | null;
}

export interface AuthContextValue extends AuthState {
  login: (email: string, password: string) => Promise<void>;
  /** Stores a JWT from the Google/GitHub callback. Returns false if it is not a usable token. */
  loginWithToken: (token: string) => boolean;
  logout: () => void;
  isAuthenticated: boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

function readInitialState(): AuthState {
  try {
    const token = localStorage.getItem(TOKEN_KEY);
    const userJson = localStorage.getItem(USER_KEY);
    if (token && userJson) {
      return { token, user: JSON.parse(userJson) as AuthUser };
    }
  } catch {
    // corrupted storage — fall through to empty state
  }
  return { user: null, token: null };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(readInitialState);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    setState({ user: null, token: null });
  }, []);

  useEffect(() => {
    configureApiClient({
      getToken: () => state.token,
      onUnauthorized: logout,
    });
  }, [state.token, logout]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await apiRequest<LoginResponse>('/auth/login', {
      method: 'POST',
      body: { email, password },
      skipAuth: true,
    });

    const { token, ...user } = response;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    setState({ token, user });
  }, []);

  // Same storage as local login. The OIDC callback only gives the JWT (employee id + role), so the
  // name and email are filled in from workforce by the effect below.
  const loginWithToken = useCallback((token: string) => {
    const claims = readTokenClaims(token);
    if (!claims) return false;
    const user = userFromClaims(claims);
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    setState({ token, user });
    return true;
  }, []);

  // Best effort: a session from Google/GitHub login has no profile yet. Without it the app still
  // works (id and role come from the token); only the header shows no name.
  const profileMissingFor = state.token && state.user && !state.user.email ? state.user.employeeId : null;
  useEffect(() => {
    if (profileMissingFor === null) return;
    let cancelled = false;
    getEmployee(profileMissingFor)
      .then((employee) => {
        if (cancelled) return;
        setState((current) => {
          if (!current.token || !current.user || current.user.employeeId !== profileMissingFor) return current;
          const user: AuthUser = {
            ...current.user,
            employeeNumber: employee.employeeNumber ?? '',
            firstName: employee.firstName ?? '',
            lastName: employee.lastName ?? '',
            email: employee.email,
          };
          localStorage.setItem(USER_KEY, JSON.stringify(user));
          return { ...current, user };
        });
      })
      .catch(() => {
        // keep the session without a profile
      });
    return () => {
      cancelled = true;
    };
  }, [profileMissingFor]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user: state.user,
      token: state.token,
      isAuthenticated: Boolean(state.token),
      login,
      loginWithToken,
      logout,
    }),
    [state, login, loginWithToken, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
