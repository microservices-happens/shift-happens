package dk.ek.shift_happens.scheduling.domain;

import java.util.Comparator;
import java.util.List;

/**
 * The rules behind scheduling.shift.understaffed.v1, kept free of Spring so they are unit-testable:
 * how many people are missing, who may take the shift, and in which order to offer them.
 */
public final class UnderstaffingRules {

    private UnderstaffingRules() {
    }

    public static int missing(int requiredCount, int assignedCount) {
        return Math.max(0, requiredCount - assignedCount);
    }

    /**
     * A candidate is active (not tombstoned), qualified for the shift, free at that time and not on
     * approved leave. This is the whole filter the event promises, nothing more.
     */
    public static boolean canTake(EmployeeReplica employee, Shift shift, boolean busy, boolean onLeave) {
        return employee.isActive() && shift.accepts(employee) && !busy && !onLeave;
    }

    /** Fewest hours this week first, then by id, so the list is stable and the LLM has to reason less. */
    public static List<Candidate> order(List<Candidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::hoursThisWeek)
                        .thenComparingLong(Candidate::employeeId))
                .toList();
    }
}
