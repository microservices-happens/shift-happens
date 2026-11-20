package dk.ek.shift_happens.scheduling.domain;

/** One entry of the AI ranking (contracts/graphql/scheduling.graphql, RankedCandidate). */
public record RankedCandidate(long employeeId, int rank) {
}
