package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logic behind scheduling.shift.understaffed.v1: how many are missing, who may take the shift,
 * and the order the list is offered in.
 */
class UnderstaffingRulesTest {

    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");

    @Test
    void onlyTheGapIsReported() {
        assertThat(UnderstaffingRules.missing(2, 2)).isZero();
        assertThat(UnderstaffingRules.missing(2, 3)).isZero();
        assertThat(UnderstaffingRules.missing(2, 1)).isEqualTo(1);
        assertThat(UnderstaffingRules.missing(1, 0)).isEqualTo(1);
    }

    @Test
    void anActiveQualifiedFreeEmployeeCanTakeTheShift() {
        assertThat(UnderstaffingRules.canTake(employee("ACTIVE", List.of(7L), null), shift(), false, false))
                .isTrue();
    }

    @Test
    void everyOneOfTheFourConditionsCanRuleAnEmployeeOut() {
        assertThat(UnderstaffingRules.canTake(employee("INACTIVE", List.of(7L), null), shift(), false, false))
                .isFalse();
        assertThat(UnderstaffingRules.canTake(employee("ACTIVE", List.of(9L), null), shift(), false, false))
                .isFalse();
        assertThat(UnderstaffingRules.canTake(employee("ACTIVE", List.of(7L), null), shift(), true, false))
                .isFalse();
        assertThat(UnderstaffingRules.canTake(employee("ACTIVE", List.of(7L), null), shift(), false, true))
                .isFalse();
    }

    @Test
    void aTombstonedEmployeeIsNeverACandidate() {
        assertThat(UnderstaffingRules.canTake(
                employee("ACTIVE", List.of(7L), Instant.parse("2026-10-01T00:00:00Z")), shift(), false, false))
                .isFalse();
    }

    @Test
    void candidatesAreOfferedLeastLoadedFirst() {
        List<Candidate> ordered = UnderstaffingRules.order(List.of(
                new Candidate(3, "C", 30.0, 37.5),
                new Candidate(2, "B", 8.0, 37.5),
                new Candidate(1, "A", 8.0, 37.5)));

        assertThat(ordered).extracting(Candidate::employeeId).containsExactly(1L, 2L, 3L);
    }

    private static Shift shift() {
        return new Shift(42L, 2, 3, "Morning", START, END, ShiftStatus.PLANNED,
                List.of(new RequiredJobRole(70L, 7, 2)), 1, false);
    }

    private static EmployeeReplica employee(String status, List<Long> jobRoleIds, Instant deletedAt) {
        return new EmployeeReplica(1, "Ada", "Lovelace", status, jobRoleIds, null, 1, deletedAt, 37.5);
    }
}
