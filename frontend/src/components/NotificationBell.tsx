import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/useAuth';
import { useNotifications } from './useNotifications';

export function NotificationBell() {
  const { isAuthenticated } = useAuth();
  const { items, unreadCount, markRead } = useNotifications(isAuthenticated);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="notif" ref={ref}>
      <button
        type="button"
        className="notif-button"
        onClick={() => setOpen((o) => !o)}
        aria-label={unreadCount > 0 ? `Notifications (${unreadCount} unread)` : 'Notifications'}
        aria-haspopup="true"
        aria-expanded={open}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden
        >
          <path d="M18 8a6 6 0 10-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 01-3.4 0" />
        </svg>
        {unreadCount > 0 && (
          <span className="notif-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>
        )}
      </button>

      {open && (
        <div className="notif-panel">
          <div className="notif-panel-title">Notifications</div>
          {items.length === 0 ? (
            <p className="notif-empty">No notifications</p>
          ) : (
            <ul className="notif-list">
              {items.map((n) => (
                <li
                  key={n.notificationId}
                  className={n.read ? 'notif-item' : 'notif-item notif-item--unread'}
                >
                  <div className="notif-item-body">
                    <span className="notif-item-title">{n.title}</span>
                    {n.message && <span>{n.message}</span>}
                    <span className="notif-item-time">
                      {new Date(n.createdAt).toLocaleString()}
                    </span>
                  </div>
                  {!n.read && (
                    <button
                      type="button"
                      className="notif-mark"
                      onClick={() => void markRead(n.notificationId)}
                    >
                      Mark read
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
