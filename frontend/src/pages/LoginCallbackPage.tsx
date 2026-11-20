import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { callbackErrorMessage, parseCallbackFragment, takePendingLogin } from '../auth/oidc';

/**
 * Landing page of the Google/GitHub login (FRONTEND_LOGIN_CALLBACK_URL). identity-service redirects
 * here with #token=<jwt> or #error=<code>. On success the session is stored like a local login; on
 * failure the user is sent back to /login with a message.
 */
export default function LoginCallbackPage() {
  const { loginWithToken } = useAuth();
  const navigate = useNavigate();
  const handled = useRef(false);

  useEffect(() => {
    // StrictMode runs effects twice in development; the fragment and the pending marker are single-use.
    if (handled.current) return;
    handled.current = true;

    const result = parseCallbackFragment(window.location.hash);
    // Drop the fragment at once, so the token does not stay in the address bar or the history entry.
    window.history.replaceState(window.history.state, '', window.location.pathname);

    const pending = takePendingLogin();
    const fail = (code: string) =>
      navigate('/login', {
        replace: true,
        state: { error: callbackErrorMessage(code, pending?.provider ?? null) },
      });

    if ('error' in result) {
      fail(result.error);
    } else if (!pending) {
      fail('not_started');
    } else if (!loginWithToken(result.token)) {
      fail('invalid_token');
    } else {
      navigate(pending.from, { replace: true });
    }
  }, [loginWithToken, navigate]);

  return (
    <div className="login-shell">
      <div className="login-card" role="status" aria-live="polite">
        <p className="login-subtitle">Completing sign-in…</p>
      </div>
    </div>
  );
}
