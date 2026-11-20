package dk.ek.shift_happens.scheduling.infrastructure.readmodel;

import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.RankedCandidate;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleSnapshot;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionSource;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import dk.ek.shift_happens.scheduling.domain.SwapDecision;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import dk.ek.shift_happens.scheduling.domain.SwapView;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Document to view mapping of the read model, in one place so the stored field names are easy to audit.
 *
 * <p>Instants are stored as BSON dates, which is what makes "overlaps this window" a range query, and
 * read back as {@link Instant}. Every other field keeps the name it has in the event contract.
 */
final class ReadModelDocuments {

    private ReadModelDocuments() {
    }

    static Document of(ShiftView shift) {
        List<Document> roles = shift.requiredJobRoles() == null ? List.of()
                : shift.requiredJobRoles().stream()
                        .map(role -> new Document("jobRoleId", role.jobRoleId())
                                .append("requiredEmployeeCount", role.requiredEmployeeCount()))
                        .toList();
        return new Document("_id", shift.shiftId())
                .append("departmentId", shift.departmentId())
                .append("workLocationId", shift.workLocationId())
                .append("shiftName", shift.shiftName())
                .append("startDatetime", date(shift.startDatetime()))
                .append("endDatetime", date(shift.endDatetime()))
                .append("status", shift.status().name())
                .append("requiredJobRoles", roles)
                .append("aggregateVersion", shift.aggregateVersion())
                .append("deletedAt", date(shift.deletedAt()));
    }

    static Document of(AssignmentView assignment) {
        return new Document("_id", assignment.shiftAssignmentId())
                .append("shiftId", assignment.shiftId())
                .append("employeeId", assignment.employeeId())
                .append("assignmentStatus", assignment.status().name())
                .append("assignedDatetime", date(assignment.assignedDatetime()))
                .append("checkInDatetime", date(assignment.checkInDatetime()))
                .append("checkOutDatetime", date(assignment.checkOutDatetime()))
                .append("shiftStartDatetime", date(assignment.shiftStartDatetime()))
                .append("shiftEndDatetime", date(assignment.shiftEndDatetime()))
                .append("aggregateVersion", assignment.aggregateVersion())
                .append("deletedAt", date(assignment.deletedAt()));
    }

    static Document of(EmployeeView employee) {
        return new Document("_id", employee.employeeId())
                .append("firstName", employee.firstName())
                .append("lastName", employee.lastName())
                .append("jobRoleIds", employee.jobRoleIds() == null ? List.of() : employee.jobRoleIds())
                .append("employmentStatus", employee.employmentStatus())
                .append("weeklyContractHours", employee.weeklyContractHours())
                .append("aggregateVersion", employee.aggregateVersion())
                .append("deletedAt", date(employee.deletedAt()));
    }
    static Document of(SwapView swap) {
        Document decision = null;
        if (swap.decision() != null) {
            decision = new Document("approverEmployeeId", swap.decision().approverEmployeeId())
                    .append("decision", swap.decision().decision().name())
                    .append("comment", swap.decision().comment())
                    .append("decisionDatetime", date(swap.decision().decisionDatetime()));
        }
        return new Document("_id", swap.shiftSwapId())
                .append("originalShiftAssignmentId", swap.originalShiftAssignmentId())
                .append("shiftId", swap.shiftId())
                .append("employeeFromId", swap.employeeFromId())
                .append("employeeToId", swap.employeeToId())
                .append("swapStatus", swap.status().name())
                .append("reason", swap.reason())
                .append("requestDatetime", date(swap.requestDatetime()))
                .append("decision", decision)
                .append("aggregateVersion", swap.aggregateVersion());
    }

    static Document suggestion(String id, SuggestionView suggestion) {
        List<Document> candidates = suggestion.candidates() == null ? List.of()
                : suggestion.candidates().stream()
                        .map(candidate -> new Document("employeeId", candidate.employeeId())
                                .append("rank", candidate.rank()))
                        .toList();
        return new Document("_id", id)
                .append("shiftId", suggestion.shiftId())
                .append("jobRoleId", suggestion.jobRoleId())
                .append("source", suggestion.source().name())
                .append("model", suggestion.model())
                .append("suggestedAt", date(suggestion.suggestedAt()))
                .append("reason", suggestion.reason())
                .append("candidates", candidates);
    }

    static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    static Instant instantOf(Document document, String field) {
        Date value = document.getDate(field);
        return value == null ? null : value.toInstant();
    }

    static long longOf(Document document, String field) {
        Number value = (Number) document.get(field);
        return value == null ? 0L : value.longValue();
    }

    static int intOf(Document document, String field) {
        Number value = (Number) document.get(field);
        return value == null ? 0 : value.intValue();
    }

    static double doubleOf(Document document, String field) {
        Number value = (Number) document.get(field);
        return value == null ? 0 : value.doubleValue();
    }

    static String stringOf(Document document, String field) {
        return (String) document.get(field);
    }
    static ShiftView toShiftView(Document document) {
        return new ShiftView(
                ((Number) document.get("_id")).longValue(),
                longOf(document, "departmentId"),
                longOf(document, "workLocationId"),
                stringOf(document, "shiftName"),
                instantOf(document, "startDatetime"),
                instantOf(document, "endDatetime"),
                ShiftStatus.valueOf(stringOf(document, "status")),
                requiredJobRolesOf(document),
                longOf(document, "aggregateVersion"),
                instantOf(document, "deletedAt"));
    }

    static AssignmentView toAssignmentView(Document document) {
        return new AssignmentView(
                ((Number) document.get("_id")).longValue(),
                longOf(document, "shiftId"),
                longOf(document, "employeeId"),
                AssignmentStatus.valueOf(stringOf(document, "assignmentStatus")),
                instantOf(document, "assignedDatetime"),
                instantOf(document, "checkInDatetime"),
                instantOf(document, "checkOutDatetime"),
                instantOf(document, "shiftStartDatetime"),
                instantOf(document, "shiftEndDatetime"),
                longOf(document, "aggregateVersion"),
                instantOf(document, "deletedAt"));
    }

    static EmployeeView toEmployeeView(Document document) {
        return new EmployeeView(
                ((Number) document.get("_id")).longValue(),
                stringOf(document, "firstName"),
                stringOf(document, "lastName"),
                jobRoleIdsOf(document),
                stringOf(document, "employmentStatus"),
                doubleOf(document, "weeklyContractHours"),
                longOf(document, "aggregateVersion"),
                instantOf(document, "deletedAt"));
    }

    static SwapView toSwapView(Document document) {
        return new SwapView(
                ((Number) document.get("_id")).longValue(),
                longOf(document, "originalShiftAssignmentId"),
                longOf(document, "shiftId"),
                longOf(document, "employeeFromId"),
                longOf(document, "employeeToId"),
                SwapStatus.valueOf(stringOf(document, "swapStatus")),
                stringOf(document, "reason"),
                instantOf(document, "requestDatetime"),
                decisionOf(document),
                longOf(document, "aggregateVersion"));
    }

    static SuggestionView toSuggestionView(Document document) {
        return new SuggestionView(
                longOf(document, "shiftId"),
                longOf(document, "jobRoleId"),
                SuggestionSource.valueOf(stringOf(document, "source")),
                stringOf(document, "model"),
                instantOf(document, "suggestedAt"),
                stringOf(document, "reason"),
                candidatesOf(document));
    }

    private static List<RequiredJobRoleSnapshot> requiredJobRolesOf(Document document) {
        List<?> roles = (List<?>) document.get("requiredJobRoles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(entry -> (Document) entry)
                .map(role -> new RequiredJobRoleSnapshot(longOf(role, "jobRoleId"),
                        intOf(role, "requiredEmployeeCount")))
                .toList();
    }

    private static List<Long> jobRoleIdsOf(Document document) {
        List<?> ids = (List<?>) document.get("jobRoleIds");
        if (ids == null) {
            return List.of();
        }
        return ids.stream().map(id -> ((Number) id).longValue()).toList();
    }

    private static SwapDecision decisionOf(Document document) {
        Document decision = (Document) document.get("decision");
        if (decision == null) {
            return null;
        }
        return new SwapDecision(longOf(decision, "approverEmployeeId"),
                ApprovalDecision.valueOf(stringOf(decision, "decision")),
                stringOf(decision, "comment"),
                instantOf(decision, "decisionDatetime"));
    }

    private static List<RankedCandidate> candidatesOf(Document document) {
        List<?> candidates = (List<?>) document.get("candidates");
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .map(entry -> (Document) entry)
                .map(candidate -> new RankedCandidate(longOf(candidate, "employeeId"),
                        intOf(candidate, "rank")))
                .toList();
    }
}
