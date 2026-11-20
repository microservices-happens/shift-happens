import { useState, type ReactNode } from 'react';
import { ApolloProvider } from '@apollo/client/react';
import { useAuth } from '../auth/useAuth';
import { createApolloClient } from './client';

/**
 * Provides a GraphQL client to the signed-in part of the app. Keyed by the token, so every session
 * gets its own client and cache (see createApolloClient).
 */
export function GraphQLProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  return <SessionClient key={token ?? 'anonymous'}>{children}</SessionClient>;
}

function SessionClient({ children }: { children: ReactNode }) {
  const [client] = useState(createApolloClient);
  return <ApolloProvider client={client}>{children}</ApolloProvider>;
}
