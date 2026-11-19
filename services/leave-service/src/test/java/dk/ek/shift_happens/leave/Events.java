package dk.ek.shift_happens.leave;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Payloads (`data`) of the events the leave projection consumes, as the contracts describe them. */
final class Events {

    static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";
    static final String SUBMITTED = "leave.request.submitted.v1";
    static final String APPROVAL_STARTED = "leave.request.approval-started.v1";
    static final String APPROVED = "leave.request.approved.v1";
    static final String REJECTED = "leave.request.rejected.v1";
    static final String CANCELLED = "leave.request.cancelled.v1";
    static final String LEDGER = "leave.ledger.entry-recorded.v1";
    static final String TYPE_CREATED = "leave.type.created.v1";
    static final String TYPE_UPDATED = "leave.type.updated.v1";
    static final String TYPE_DELETED = "leave.type.deleted.v1";

    static final List<String> BOUND = List.of(EMPLOYEE_CREATED, EMPLOYEE_UPDATED, EMPLOYEE_DELETED, SUBMITTED,
            APPROVAL_STARTED, APPROVED, REJECTED, CANCELLED, LEDGER, TYPE_CREATED, TYPE_UPDATED, TYPE_DELETED);

    private Events() {
    }

    /** employee.schema.json, including the fields the projection must not keep. */
    static Map<String, Object> employee(long id, String firstName, String lastName) {
        Map<String, Object> data = new HashMap<>();
        data.put("employeeId", id);
        data.put("employeeNumber", "E-" + id);
        data.put("userRole", "Employee");
        data.put("firstName", firstName);
        data.put("lastName", lastName);
        data.put("email", firstName.toLowerCase() + id + "@shift.dk");
        data.put("employmentStatus", "ACTIVE");
        data.put("jobRoleIds", List.of(7));
        data.put("weeklyContractHours", 37.5);
        data.put("salary", 123456);
        return data;
    }

    static Map<String, Object> tombstone(String entityType, long id) {
        return Map.of("entityType", entityType, "entityId", id, "deletedAt", Instant.now().toString());
    }

    static Map<String, Object> type(long id, String name) {
        return type(id, name, null, true, true);
    }

    static Map<String, Object> type(long id, String name, String description, boolean requiresApproval,
                                    boolean paid) {
        Map<String, Object> data = new HashMap<>();
        data.put("leaveTypeId", id);
        data.put("leaveTypeName", name);
        data.put("leaveTypeDescription", description);
        data.put("requiresApproval", requiresApproval);
        data.put("isPaidLeave", paid);
        return data;
    }

    static Map<String, Object> request(long id, long employeeId, long typeId, String typeName, String start,
                                       String end, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("leaveRequestId", id);
        data.put("employeeId", employeeId);
        data.put("leaveTypeId", typeId);
        data.put("leaveTypeName", typeName);
        data.put("startDate", start);
        data.put("endDate", end);
        data.put("requestStatus", status);
        data.put("reason", "family");
        data.put("requestedDatetime", "2027-01-01T10:00:00.123456Z");
        data.put("decision", null);
        data.put("rejectionReason", null);
        return data;
    }

    static Map<String, Object> decided(Map<String, Object> request, Long approvalId, long approver, String decision,
                                       String comment) {
        Map<String, Object> data = new HashMap<>(request);
        Map<String, Object> verdict = new HashMap<>();
        if (approvalId != null) {
            verdict.put("leaveApprovalId", approvalId);
        }
        verdict.put("approverEmployeeId", approver);
        verdict.put("decision", decision);
        verdict.put("comment", comment);
        verdict.put("decisionDatetime", "2027-01-02T09:30:00.5Z");
        data.put("decision", verdict);
        return data;
    }

    static Map<String, Object> ledger(long id, long employeeId, long typeId, String change, String balanceAfter,
                                      String transactionType) {
        Map<String, Object> data = new HashMap<>();
        data.put("leaveLedgerId", id);
        data.put("employeeId", employeeId);
        data.put("leaveTypeId", typeId);
        data.put("changeAmountDays", new BigDecimal(change));
        data.put("balanceAfterDays", new BigDecimal(balanceAfter));
        data.put("transactionType", transactionType);
        data.put("referenceEntityType", null);
        data.put("referenceEntityId", null);
        data.put("transactionDatetime", "2027-01-01T08:00:00Z");
        return data;
    }
}
