Spring for GraphQL loads `scheduling.graphqls` from here. It is a copy of
`contracts/graphql/scheduling.graphql`, which stays the source of truth: `GraphQlSchemaMatchesContractTest` fails the
build when the two drift apart.
