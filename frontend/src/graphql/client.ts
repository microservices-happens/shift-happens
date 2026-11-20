import { ApolloClient, ApolloLink, HttpLink, InMemoryCache } from '@apollo/client';
import { CombinedGraphQLErrors, ServerError } from '@apollo/client/errors';
import { SetContextLink } from '@apollo/client/link/context';
import { ErrorLink } from '@apollo/client/link/error';
import { currentToken, reportUnauthorized } from '../api/client';

// Scheduling reads: the gateway routes POST /api/graphql to scheduling-query's /graphql
// (docker-compose.microservices.yml); `npm run dev` proxies /api/* to the gateway (vite.config.ts).
const GRAPHQL_URL = import.meta.env.VITE_GRAPHQL_URL || '/api/graphql';

/** The same Bearer JWT as REST (contracts/README.md, "Authentication and authorization"). */
const authLink = new SetContextLink((prevContext) => {
  const token = currentToken();
  return {
    headers: {
      ...prevContext.headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  };
});

/** An expired or missing token is a 401 from Spring Security, before GraphQL runs: log out as REST does. */
const unauthorizedLink = new ErrorLink(({ error }) => {
  if (ServerError.is(error) && error.statusCode === 401) reportUnauthorized();
});

/**
 * One client per session: what a viewer may see depends on the token (an Employee only gets their own
 * shifts), so a new token gets a new, empty cache instead of clearing a shared one under running queries.
 */
export function createApolloClient(): ApolloClient {
  return new ApolloClient({
    link: ApolloLink.from([unauthorizedLink, authLink, new HttpLink({ uri: GRAPHQL_URL })]),
    cache: new InMemoryCache({
      // The contract's ids are not called `id`, so name them for normalisation. Types without an id
      // (RequiredJobRole, ReplacementSuggestion, RankedCandidate) stay embedded in their shift.
      typePolicies: {
        Shift: { keyFields: ['shiftId'] },
        Assignment: { keyFields: ['shiftAssignmentId'] },
        Employee: { keyFields: ['employeeId'] },
        SwapRequest: { keyFields: ['shiftSwapId'] },
      },
    }),
    defaultOptions: {
      // The read model is eventually consistent (< 1 s behind a write), so show the cache at once but
      // always ask the server again when a page mounts.
      watchQuery: { fetchPolicy: 'cache-and-network' },
    },
  });
}

/** A readable message for a failed query: the server's GraphQL error (e.g. FORBIDDEN) or the HTTP failure. */
export function graphqlErrorMessage(error: unknown, fallback: string): string {
  if (CombinedGraphQLErrors.is(error)) {
    return error.errors.map((e) => e.message).join('; ') || fallback;
  }
  if (ServerError.is(error)) return `${fallback} (${error.statusCode})`;
  return error instanceof Error && error.message ? error.message : fallback;
}
