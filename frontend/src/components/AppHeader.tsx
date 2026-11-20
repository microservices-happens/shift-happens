import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { NotificationBell } from './NotificationBell';

interface AppHeaderProps {
  onToggleMenu: () => void;
  sidebarOpen: boolean;
}

function initial(s: string | null | undefined): string {
  return (s?.[0] ?? '').toUpperCase();
}

export function AppHeader({ onToggleMenu, sidebarOpen }: AppHeaderProps) {
  const { user, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!menuOpen) return;
    function onDocMouseDown(e: MouseEvent) {
      if (!menuRef.current?.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setMenuOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen]);

  const initials =
    (initial(user?.firstName) + initial(user?.lastName)) || 'U';

  return (
    <header className="app-header">
      <div className="app-header-left">
        <button
          type="button"
          className="app-header-burger"
          onClick={onToggleMenu}
          aria-label={sidebarOpen ? 'Close menu' : 'Open menu'}
          aria-expanded={sidebarOpen}
        >
          <BurgerIcon />
        </button>
        <Link to="/" className="app-header-brand">
          <div className="app-brand-mark">S</div>
          <span className="app-brand-name">Shift Happens</span>
        </Link>
      </div>

      <div className="app-header-right" ref={menuRef}>
        <NotificationBell />
        <button
          type="button"
          className="app-header-user"
          onClick={() => setMenuOpen((o) => !o)}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
        >
          <span className="app-user-avatar">{initials}</span>
          <span className="app-header-user-meta">
            <span className="app-user-name">
              {user?.firstName} {user?.lastName}
            </span>
            <span className="app-user-role">{user?.roleName ?? ''}</span>
          </span>
          <ChevronIcon />
        </button>

        {menuOpen && (
          <div className="app-header-menu" role="menu">
            <div className="app-header-menu-header">
              <span className="app-user-name">
                {user?.firstName} {user?.lastName}
              </span>
              <span className="app-user-role">{user?.email}</span>
            </div>
            <div className="app-header-menu-sep" />
            <Link
              to="/settings"
              role="menuitem"
              className="app-header-menu-item"
              onClick={() => setMenuOpen(false)}
            >
              Account settings
            </Link>
            <button
              type="button"
              role="menuitem"
              className="app-header-menu-item app-header-menu-item--danger"
              onClick={() => {
                setMenuOpen(false);
                logout();
              }}
            >
              Log out
            </button>
          </div>
        )}
      </div>
    </header>
  );
}

function BurgerIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden
    >
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M6 9l6 6 6-6" />
    </svg>
  );
}
