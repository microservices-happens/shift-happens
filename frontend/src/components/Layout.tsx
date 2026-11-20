import { useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { GraphQLProvider } from '../graphql/GraphQLProvider';
import { AppHeader } from './AppHeader';
import { NavSidebar } from './NavSidebar';

const STORAGE_KEY = 'shift_happens_sidebar_open';

function readInitial(): boolean {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw === null ? true : raw === 'true';
  } catch {
    return true;
  }
}

export function Layout() {
  const [open, setOpen] = useState<boolean>(readInitial);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, String(open));
    } catch {
      /* ignore */
    }
  }, [open]);

  return (
    <div className="app-shell">
      <AppHeader onToggleMenu={() => setOpen((o) => !o)} sidebarOpen={open} />
      <div className="app-body">
        <NavSidebar collapsed={!open} />
        <main className="app-content">
          {/* Only signed-in pages read GraphQL; one client per session (src/graphql). */}
          <GraphQLProvider>
            <Outlet />
          </GraphQLProvider>
        </main>
      </div>
    </div>
  );
}
