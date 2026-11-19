package dk.ek.shift_happens.leave.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.leave.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import dk.ek.shift_happens.leave.domain.LeaveTypeSnapshot;
import dk.ek.shift_happens.leave.domain.LedgerEntryRecorded;
import dk.ek.shift_happens.leave.domain.ReadModelWriter;
import dk.ek.shift_happens.leave.infrastructure.messaging.ProjectionEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Projects the events of the leave.projection queue into the Mongo read model.
 *
 * <p>The store is a standalone MongoDB: no multi-document transactions. The deviation from "record the eventId in the
 * same transaction as the change" is deliberate and safe because every change is idempotent and guarded by itself (a
 * snapshot only replaces an older version, a tombstone always wins, a ledger entry is inserted by its id). The
 * eventId is recorded after the change, so a crash in between only means the event is applied a second time with no
 * effect. {@code processed_events} is therefore a fast path, not the source of correctness. An event whose type is
 * not handled is recorded and ignored.
 */
@Service
@Profile("query")
public class ProjectionService {

    public static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    public static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    public static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";
    public static final String REQUEST_SUBMITTED = "leave.request.submitted.v1";
    public static final String REQUEST_APPROVAL_STARTED = "leave.request.approval-started.v1";
    public static final String REQUEST_APPROVED = "leave.request.approved.v1";
    public static final String REQUEST_REJECTED = "leave.request.rejected.v1";
    public static final String REQUEST_CANCELLED = "leave.request.cancelled.v1";
    public static final String LEDGER_ENTRY_RECORDED = "leave.ledger.entry-recorded.v1";
    public static final String TYPE_CREATED = "leave.type.created.v1";
    public static final String TYPE_UPDATED = "leave.type.updated.v1";
    public static final String TYPE_DELETED = "leave.type.deleted.v1";

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private final ReadModelWriter readModel;
    private final ObjectMapper objectMapper;

    ProjectionService(ReadModelWriter readModel, ObjectMapper objectMapper) {
        this.readModel = readModel;
        this.objectMapper = objectMapper;
    }

    public void handle(ProjectionEnvelope envelope) {
        if (readModel.isProcessed(envelope.eventId())) {
            log.debug("Ignoring already processed event {}", envelope.eventId());
            return;
        }
        switch (envelope.eventType()) {
            case EMPLOYEE_CREATED, EMPLOYEE_UPDATED -> employee(envelope);
            case EMPLOYEE_DELETED -> employeeTombstone(envelope);
            case REQUEST_SUBMITTED, REQUEST_APPROVAL_STARTED, REQUEST_APPROVED, REQUEST_REJECTED, REQUEST_CANCELLED ->
                    leaveRequest(envelope);
            case LEDGER_ENTRY_RECORDED -> ledgerEntry(envelope);
            case TYPE_CREATED, TYPE_UPDATED -> leaveType(envelope);
            case TYPE_DELETED -> leaveTypeTombstone(envelope);
            default -> log.debug("Ignoring unhandled event type {} ({})", envelope.eventType(), envelope.eventId());
        }
        readModel.markProcessed(envelope.eventId());
    }

    /** Reads the name only: salary, e-mail and the rest of the snapshot never reach the read model. */
    private void employee(ProjectionEnvelope envelope) {
        JsonNode data = data(envelope);
        long employeeId = integer(data, "employeeId", envelope);
        readModel.applyEmployee(employeeId, text(data, "firstName"), text(data, "lastName"),
                envelope.aggregateVersion());
    }

    private void employeeTombstone(ProjectionEnvelope envelope) {
        JsonNode data = data(envelope);
        readModel.tombstoneEmployee(integer(data, "entityId", envelope), envelope.aggregateVersion(),
                deletedAt(data, envelope));
    }

    private void leaveType(ProjectionEnvelope envelope) {
        JsonNode data = data(envelope);
        integer(data, "leaveTypeId", envelope);
        requireText(data, "leaveTypeName", envelope);
        readModel.applyLeaveType(convert(data, LeaveTypeSnapshot.class, envelope), envelope.aggregateVersion());
    }

    private void leaveTypeTombstone(ProjectionEnvelope envelope) {
        JsonNode data = data(envelope);
        readModel.tombstoneLeaveType(integer(data, "entityId", envelope), envelope.aggregateVersion(),
                deletedAt(data, envelope));
    }

    private void leaveRequest(ProjectionEnvelope envelope) {
        JsonNode data = data(envelope);
        for (String field : new String[] {"leaveRequestId", "employeeId", "leaveTypeId"}) {
            integer(data, field, envelope);
        }
        for (String field : new String[] {"startDate", "endDate", "requestStatus"}) {
            requireText(data, field, envelope);
        }
        LeaveRequestStatus.parse(data.path("requestStatus").asText()); // rejects a status this service does not know
        readModel.applyLeaveRequest(convert(data, LeaveRequestSnapshot.class, envelope), envelope.aggregateVersion());
    }

    private void ledgerEntry(ProjectionEnvelope envelope) {
        JsonNode data = data(envelope);
        for (String field : new String[] {"leaveLedgerId", "employeeId", "leaveTypeId"}) {
            integer(data, field, envelope);
        }
        if (!data.path("changeAmountDays").isNumber()) {
            throw malformed(envelope, "changeAmountDays is missing or not a number");
        }
        readModel.recordLedgerEntry(convert(data, LedgerEntryRecorded.class, envelope));
    }

    private <T> T convert(JsonNode data, Class<T> type, ProjectionEnvelope envelope) {
        try {
            return objectMapper.treeToValue(data, type);
        } catch (JsonProcessingException e) {
            throw malformed(envelope, "data does not match " + type.getSimpleName() + ": " + e.getOriginalMessage());
        }
    }

    private static JsonNode data(ProjectionEnvelope envelope) {
        if (envelope.data() == null || !envelope.data().isObject()) {
            throw malformed(envelope, "data is missing");
        }
        return envelope.data();
    }

    private static long integer(JsonNode data, String field, ProjectionEnvelope envelope) {
        JsonNode value = data.path(field);
        if (!value.isIntegralNumber()) {
            throw malformed(envelope, field + " is missing or not an integer");
        }
        return value.asLong();
    }

    private static void requireText(JsonNode data, String field, ProjectionEnvelope envelope) {
        if (!data.path(field).isTextual() || data.path(field).asText().isBlank()) {
            throw malformed(envelope, field + " is missing");
        }
    }

    private static String text(JsonNode data, String field) {
        return data.path(field).isTextual() ? data.path(field).asText() : null;
    }

    private static Instant deletedAt(JsonNode data, ProjectionEnvelope envelope) {
        return data.path("deletedAt").isTextual() ? Instant.parse(data.path("deletedAt").asText())
                : envelope.occurredAt();
    }

    private static IllegalArgumentException malformed(ProjectionEnvelope envelope, String problem) {
        return new IllegalArgumentException("event " + envelope.eventId() + " (" + envelope.eventType() + "): "
                + problem);
    }
}
