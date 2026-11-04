package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.Candidate;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplica;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.scheduling.domain.LeaveWindowRepository;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import dk.ek.shift_happens.scheduling.domain.Understaffed;
import dk.ek.shift_happens.scheduling.domain.UnderstaffingRules;
import dk.ek.shift_happens.scheduling.domain.WorkedWindow;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes scheduling.shift.understaffed.v1 when a released or cancelled assignment leaves a shift
 * short of a job role (P2.12), with the candidates ai-service needs already filtered.
 *
 * <p>Everything here is local: the employee replica (qualified, active, contract hours), this
 * service's own assignments (who is busy, hours this week) and the copied leave windows. Nobody is
 * called synchronously, so a slow Workforce or Leave cannot stall an assignment.
 */
@Service
@Profile("command")
public class UnderstaffingService {

    private final ShiftRepository shifts;
    private final ShiftAssignmentRepository assignments;
    private final EmployeeReplicaRepository employees;
    private final LeaveWindowRepository leaveWindows;
    private final DomainEvents events;
    private final Clock clock;

    UnderstaffingService(ShiftRepository shifts, ShiftAssignmentRepository assignments,
                         EmployeeReplicaRepository employees, LeaveWindowRepository leaveWindows,
                         DomainEvents events, Clock clock) {
        this.shifts = shifts;
        this.assignments = assignments;
        this.employees = employees;
        this.leaveWindows = leaveWindows;
        this.events = events;
        this.clock = clock;
    }

    /** Called after an assignment is cancelled, released by the saga, or deleted. */
    public void publishIfShort(long shiftId, String correlationId, UUID causationId) {
        Shift shift = shifts.findById(shiftId).filter(found -> !found.deleted()).orElse(null);
        if (shift == null || shift.status().isCancelled() || shift.requiredJobRoles().isEmpty()) {
            return;
        }

        List<ShiftAssignment> onShift = assignments.findActiveByShift(shiftId);
        List<EmployeeReplica> pool = employees.findAll();
        Set<Long> busy = busyEmployeeIds(shift);
        Map<Long, Double> hoursThisWeek = new HashMap<>();

        for (RequiredJobRole required : shift.requiredJobRoles()) {
            int assigned = (int) onShift.stream()
                    .filter(assignment -> hasJobRole(assignment.employeeId(), required.jobRoleId()))
                    .count();
            int missing = UnderstaffingRules.missing(required.requiredEmployeeCount(), assigned);
            if (missing == 0) {
                continue;
            }

            events.append(DomainEvents.SHIFT_UNDERSTAFFED, shift.shiftId(), shift.aggregateVersion(),
                    correlationId, causationId, new Understaffed(shift.shiftId(), shift.shiftName(),
                            shift.departmentId(), shift.startDatetime(), shift.endDatetime(),
                            required.jobRoleId(), missing, candidates(shift, pool, busy, hoursThisWeek)));
        }
    }

    private List<Candidate> candidates(Shift shift, List<EmployeeReplica> pool, Set<Long> busy,
                                       Map<Long, Double> hoursCache) {
        LocalDate shiftDate = LocalDate.ofInstant(shift.startDatetime(), ZoneOffset.UTC);
        List<Candidate> candidates = new ArrayList<>();

        for (EmployeeReplica employee : pool) {
            boolean onLeave = !leaveWindows.findApprovedOverlapping(employee.employeeId(), shiftDate,
                    shiftDate).isEmpty();
            if (!UnderstaffingRules.canTake(employee, shift, busy.contains(employee.employeeId()), onLeave)) {
                continue;
            }
            double worked = hoursCache.computeIfAbsent(employee.employeeId(),
                    id -> hoursThisWeek(id, shift));
            candidates.add(new Candidate(employee.employeeId(), employee.fullName().trim(), worked,
                    employee.weeklyContractHours()));
        }
        return UnderstaffingRules.order(candidates);
    }

    /** Employees already working an overlapping shift elsewhere; this shift itself does not count. */
    private Set<Long> busyEmployeeIds(Shift shift) {
        Set<Long> busy = new HashSet<>();
        for (ShiftAssignment assignment : assignments.findActiveOverlappingOtherThanShift(shift.shiftId(),
                shift.startDatetime(), shift.endDatetime())) {
            busy.add(assignment.employeeId());
        }
        return busy;
    }

    /** Hours already scheduled in the ISO week of the shift, in UTC. */
    private double hoursThisWeek(long employeeId, Shift shift) {
        LocalDate monday = LocalDate.ofInstant(shift.startDatetime(), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant from = monday.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = from.plus(7, ChronoUnit.DAYS);
        return assignments.findWorkedWindows(employeeId, from, to).stream()
                .mapToDouble(WorkedWindow::hours)
                .sum();
    }

    /** An employee missing from the replica was validated when assigned, so count them as qualified. */
    private boolean hasJobRole(long employeeId, long jobRoleId) {
        return employees.findByEmployeeId(employeeId)
                .map(employee -> employee.hasJobRole(jobRoleId))
                .orElse(true);
    }
}
