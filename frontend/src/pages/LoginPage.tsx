import { useEffect, useState, type FormEvent } from 'react';
import { useLocation, useNavigate, Navigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { ApiError } from '../api/types';
import {
  OIDC_PROVIDERS,
  authorizeErrorMessage,
  authorizeUrl,
  checkProviderAvailable,
  rememberPendingLogin,
  type OidcProvider,
} from '../auth/oidc';

export default function LoginPage() {
  const { login, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // /login/callback sends failed Google/GitHub logins back here with a message.
  const [error, setError] = useState<string | null>(
    () => (location.state as { error?: string } | null)?.error ?? null
  );
  const [redirectingTo, setRedirectingTo] = useState<OidcProvider | null>(null);

  // Coming back with the browser's Back button from the provider restores this page from the
  // back/forward cache with the buttons still disabled.
  useEffect(() => {
    const reset = (event: PageTransitionEvent) => {
      if (event.persisted) setRedirectingTo(null);
    };
    window.addEventListener('pageshow', reset);
    return () => window.removeEventListener('pageshow', reset);
  }, []);

  if (isAuthenticated) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  async function handleProviderLogin(provider: OidcProvider) {
    setError(null);
    setRedirectingTo(provider);
    const availability = await checkProviderAvailable(provider);
    if (!availability.ok) {
      setError(authorizeErrorMessage(provider, availability.status));
      setRedirectingTo(null);
      return;
    }
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    rememberPendingLogin(provider, from);
    window.location.assign(authorizeUrl(provider));
  }

  const busy = submitting || redirectingTo !== null;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      const from = (location.state as { from?: string } | null)?.from ?? '/';
      navigate(from, { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Could not reach the server. Is the backend running?');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="mb-2 flex items-center gap-3">
          <div className="app-brand-mark">S</div>
          <div>
            <h1 className="text-xl font-bold leading-tight tracking-tight">
              Shift Happens
            </h1>
            <p className="login-subtitle">Sign in to continue</p>
          </div>
        </div>

        <label className="login-field">
          <span>Email</span>
          <input
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>

        <label className="login-field">
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>

        {error && <div className="login-error">{error}</div>}

        <button type="submit" disabled={busy} className="login-submit">
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>

        <div className="flex items-center gap-3 text-xs uppercase tracking-wide text-slate-400">
          <span className="h-px flex-1 bg-slate-200" />
          or
          <span className="h-px flex-1 bg-slate-200" />
        </div>

        <div className="flex flex-col gap-2">
          {OIDC_PROVIDERS.map(({ id, label }) => (
            <button
              key={id}
              type="button"
              disabled={busy}
              onClick={() => void handleProviderLogin(id)}
              className="rounded-md border border-slate-300 bg-white px-4 py-2.5 font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {redirectingTo === id ? `Redirecting to ${label}…` : `Log in with ${label}`}
            </button>
          ))}
        </div>
      </form>
    </div>
  );
}
