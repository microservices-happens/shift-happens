package dk.ek.shift_happens.scheduling.domain;

/** One entry of candidates in scheduling.shift.understaffed.v1 (contracts/events/payloads/understaffed.schema.json). */
public record Candidate(
        long employeeId,
        String name,
        double hoursThisWeek,
        double weeklyContractHours) {
}
