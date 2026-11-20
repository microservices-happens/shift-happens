package dk.ek.shift_happens.scheduling.domain;

/**
 * A required job role with the staffing the read model can count (contracts/graphql/scheduling.graphql,
 * RequiredJobRole). {@code assignedCount} counts active assignments whose employee holds the role, which
 * is why the read model needs both the assignments and the employee replica.
 */
public record RequiredJobRoleView(long jobRoleId, int requiredEmployeeCount, int assignedCount) {

    public boolean understaffed() {
        return UnderstaffingRules.missing(requiredEmployeeCount, assignedCount) > 0;
    }
}
