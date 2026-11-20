import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_');
  // `npm run dev` forwards /api/* unchanged to the API gateway, which routes /api/v1/<resource>
  // and /api/graphql to the owning service (docker-compose.microservices.yml, Caddyfile).
  // Caddy serves a locally generated certificate, hence `secure: false`.
  const gateway = env.VITE_DEV_PROXY_TARGET || 'https://localhost:8443';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: gateway,
          changeOrigin: true,
          secure: false,
        },
      },
    },
  };
});
