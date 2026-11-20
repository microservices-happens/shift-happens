import { useCallback, useEffect, useState } from 'react';
import { listNotifications, markNotificationRead } from '../api/notifications';
import type { AppNotification } from '../api/types';

const POLL_MS = 30_000;
const MAX_BACKOFF_MS = 5 * 60_000;

/**
 * Polls GET /notifications while `enabled` and the tab is visible. Failures (e.g. the service is
 * not deployed) are swallowed: the list stays empty and the delay doubles up to 5 minutes.
 */
export function useNotifications(enabled: boolean) {
  const [items, setItems] = useState<AppNotification[]>([]);

  useEffect(() => {
    if (!enabled) {
      setItems([]);
      return;
    }
    let cancelled = false;
    let failures = 0;
    let timer: number | undefined;

    const schedule = () => {
      window.clearTimeout(timer);
      if (cancelled || document.hidden) return;
      timer = window.setTimeout(poll, Math.min(POLL_MS * 2 ** failures, MAX_BACKOFF_MS));
    };

    const poll = async () => {
      window.clearTimeout(timer);
      try {
        const data = await listNotifications();
        if (cancelled) return;
        failures = 0;
        setItems(Array.isArray(data) ? data : []);
      } catch {
        if (cancelled) return;
        failures += 1;
        setItems([]);
      }
      schedule();
    };

    const onVisibility = () => {
      if (document.hidden) window.clearTimeout(timer);
      else void poll();
    };

    document.addEventListener('visibilitychange', onVisibility);
    if (!document.hidden) void poll();

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [enabled]);

  const markRead = useCallback(async (id: number) => {
    const set = (read: boolean) =>
      setItems((prev) => prev.map((n) => (n.notificationId === id ? { ...n, read } : n)));
    set(true);
    try {
      await markNotificationRead(id);
    } catch {
      set(false);
    }
  }, []);

  const unreadCount = items.filter((n) => !n.read).length;
  return { items, unreadCount, markRead };
}
