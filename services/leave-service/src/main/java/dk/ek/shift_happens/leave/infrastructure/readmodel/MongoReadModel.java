package dk.ek.shift_happens.leave.infrastructure.readmodel;

import dk.ek.shift_happens.leave.domain.ApprovalDecision;
import dk.ek.shift_happens.leave.domain.BalanceKey;
import dk.ek.shift_happens.leave.domain.LeaveDecision;
import dk.ek.shift_happens.leave.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import dk.ek.shift_happens.leave.domain.LeaveTypeSnapshot;
import dk.ek.shift_happens.leave.domain.LedgerEntryRecorded;
import dk.ek.shift_happens.leave.domain.ProjectedEmployee;
import dk.ek.shift_happens.leave.domain.ReadModelReader;
import dk.ek.shift_happens.leave.domain.ReadModelWriter;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter: the read model in MongoDB, through {@link MongoTemplate} and plain documents (no repositories, no mapping
 * annotations). Query role only.
 *
 * <p>The database is a standalone mongod, so there are no multi-document transactions and nothing here relies on
 * one. Each write is a single-document operation that is idempotent and carries its own guard:
 * <ul>
 *   <li>a snapshot is an upsert whose filter demands {@code version < incoming} (and, for employees,
 *       {@code deleted != true}); when the document exists but the filter does not match, the upsert tries to insert
 *       the same {@code _id}; a duplicate key on that insert is retried once as a plain update with the same filter
 *       (mongod retries upsert duplicates itself only for equality-only filters), so competing consumers cannot lose
 *       a newer snapshot, and a stale or tombstoned snapshot simply matches nothing;</li>
 *   <li>leave types: {@code version} is the version of the snapshot fields; the tombstone sets a sticky
 *       {@code deleted} and {@code deletedVersion} and never touches {@code version}, so the last name converges in
 *       any order; a deleted type keeps serving its last name to the overview, as the command database does;</li>
 *   <li>a tombstone is an upsert that sets {@code deleted}, keeps the highest version and never removes the document;</li>
 *   <li>a ledger entry is inserted under {@code _id = leaveLedgerId}; a duplicate key means it was already applied.</li>
 * </ul>
 * Dates are stored as ISO-8601 strings (no millisecond truncation, and they sort correctly); ids as int64; day amounts
 * as Decimal128, so the balance sum is exact.
 */
@Repository
@Profile("query")
class MongoReadModel implements ReadModelWriter, ReadModelReader {

    static final String EMPLOYEES = "employees";
    static final String LEAVE_TYPES = "leave_types";
    static final String LEAVE_REQUESTS = "leave_requests";
    static final String LEDGER_ENTRIES = "ledger_entries";
    static final String PROCESSED_EVENTS = "processed_events";

    private final MongoTemplate mongo;

    MongoReadModel(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    @Override
    public boolean isProcessed(UUID eventId) {
        return mongo.exists(Query.query(Criteria.where("_id").is(eventId.toString())), PROCESSED_EVENTS);
    }

    @Override
    public void markProcessed(UUID eventId) {
        insertIgnoringDuplicate(new Document("_id", eventId.toString()).append("processedAt", Instant.now().toString()),
                PROCESSED_EVENTS);
    }

    @Override
    public void applyEmployee(long employeeId, String firstName, String lastName, long version) {
        upsertNewer(EMPLOYEES, employeeId, version, true, new Document("firstName", firstName)
                .append("lastName", lastName).append("deleted", false));
    }

    @Override
    public void tombstoneEmployee(long employeeId, long version, Instant deletedAt) {
        tombstone(EMPLOYEES, employeeId, version, deletedAt);
    }

    @Override
    public void applyLeaveType(LeaveTypeSnapshot snapshot, long version) {
        upsertNewer(LEAVE_TYPES, snapshot.leaveTypeId(), version, false, new Document("leaveTypeName",
                snapshot.leaveTypeName()).append("leaveTypeDescription", snapshot.leaveTypeDescription())
                .append("requiresApproval", snapshot.requiresApproval()).append("isPaidLeave", snapshot.isPaidLeave()));
    }

    @Override
    public void tombstoneLeaveType(long leaveTypeId, long version, Instant deletedAt) {
        // Never touches "version" (the version of the snapshot fields); 0 only when it creates the document, so a
        // later snapshot's "version < v" guard matches.
        mongo.upsert(Query.query(Criteria.where("_id").is(leaveTypeId)),
                new Update().set("deleted", true).set("deletedAt", instant(deletedAt))
                        .max("deletedVersion", version).setOnInsert("version", 0L),
                LEAVE_TYPES);
    }

    @Override
    public void applyLeaveRequest(LeaveRequestSnapshot s, long version) {
        Document decision = s.decision() == null ? null : new Document("leaveApprovalId", s.decision().leaveApprovalId())
                .append("approverEmployeeId", s.decision().approverEmployeeId())
                .append("decision", s.decision().decision().name())
                .append("comment", s.decision().comment())
                .append("decisionDatetime", instant(s.decision().decisionDatetime()));
        upsertNewer(LEAVE_REQUESTS, s.leaveRequestId(), version, false, new Document("employeeId", s.employeeId())
                .append("leaveTypeId", s.leaveTypeId())
                .append("leaveTypeName", s.leaveTypeName())
                .append("startDate", s.startDate().toString())
                .append("endDate", s.endDate().toString())
                .append("requestStatus", s.requestStatus())
                .append("reason", s.reason())
                .append("requestedDatetime", instant(s.requestedDatetime()))
                .append("decision", decision)
                .append("rejectionReason", s.rejectionReason()));
    }

    @Override
    public void recordLedgerEntry(LedgerEntryRecorded e) {
        insertIgnoringDuplicate(new Document("_id", e.leaveLedgerId())
                .append("employeeId", e.employeeId())
                .append("leaveTypeId", e.leaveTypeId())
                .append("changeAmountDays", new Decimal128(e.changeAmountDays()))
                .append("balanceAfterDays", e.balanceAfterDays() == null ? null : new Decimal128(e.balanceAfterDays()))
                .append("transactionType", e.transactionType())
                .append("referenceEntityType", e.referenceEntityType())
                .append("referenceEntityId", e.referenceEntityId())
                .append("transactionDatetime", instant(e.transactionDatetime())), LEDGER_ENTRIES);
    }

    /**
     * Only a newer version replaces the stored one. {@code guardDeleted}: a tombstoned document is never revived
     * (employees). A rejected guard surfaces as a duplicate key on the upsert's insert and is retried once as a plain
     * update with the same filter, which then matches nothing (stale or tombstoned) or applies the snapshot (a
     * concurrent consumer had inserted an older one).
     */
    private void upsertNewer(String collection, long id, long version, boolean guardDeleted, Document fields) {
        Criteria match = Criteria.where("_id").is(id).and("version").lt(version);
        if (guardDeleted) {
            match = match.and("deleted").ne(true);
        }
        Update update = new Update().set("version", version);
        fields.forEach(update::set);
        try {
            mongo.upsert(new Query(match), update, collection);
        } catch (DuplicateKeyException stale) {
            // Another consumer inserted the document first, or it is stale or tombstoned. Apply as a plain update:
            // the same guard decides (no match = stale or tombstoned).
            mongo.updateFirst(new Query(match), update, collection);
        }
    }

    private void tombstone(String collection, long id, long version, Instant deletedAt) {
        mongo.upsert(Query.query(Criteria.where("_id").is(id)),
                new Update().max("version", version).set("deleted", true).set("deletedAt", instant(deletedAt)),
                collection);
    }

    private void insertIgnoringDuplicate(Document document, String collection) {
        try {
            mongo.insert(document, collection);
        } catch (DuplicateKeyException alreadyThere) {
            // idempotent: the same id was applied before
        }
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    @Override
    public List<LeaveTypeSnapshot> activeLeaveTypes() {
        return mongo.find(Query.query(Criteria.where("deleted").ne(true)).with(Sort.by("_id")), Document.class,
                LEAVE_TYPES).stream().map(MongoReadModel::leaveType).toList();
    }

    @Override
    public Optional<LeaveTypeSnapshot> activeLeaveType(long leaveTypeId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(leaveTypeId)
                        .and("deleted").ne(true)), Document.class, LEAVE_TYPES))
                .filter(document -> document.getString("leaveTypeName") != null)
                .map(MongoReadModel::leaveType);
    }

    @Override
    public List<LeaveRequestSnapshot> leaveRequests() {
        return leaveRequests(null, null);
    }

    @Override
    public Optional<LeaveRequestSnapshot> leaveRequest(long leaveRequestId) {
        return Optional.ofNullable(mongo.findById(leaveRequestId, Document.class, LEAVE_REQUESTS))
                .map(MongoReadModel::leaveRequest);
    }

    @Override
    public List<LeaveRequestSnapshot> leaveRequests(Long employeeId, LeaveRequestStatus status) {
        Query query = new Query().with(Sort.by("_id"));
        if (employeeId != null) {
            query.addCriteria(Criteria.where("employeeId").is(employeeId));
        }
        if (status != null) {
            query.addCriteria(Criteria.where("requestStatus").is(status.name()));
        }
        return mongo.find(query, Document.class, LEAVE_REQUESTS).stream().map(MongoReadModel::leaveRequest).toList();
    }

    @Override
    public List<LeaveRequestSnapshot> decidedLeaveRequests() {
        return mongo.find(Query.query(Criteria.where("decision").ne(null)).with(Sort.by("_id")), Document.class,
                LEAVE_REQUESTS).stream().map(MongoReadModel::leaveRequest).toList();
    }

    @Override
    public Optional<LeaveRequestSnapshot> decidedLeaveRequestByApprovalId(long leaveApprovalId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("decision.leaveApprovalId")
                        .is(leaveApprovalId)), Document.class, LEAVE_REQUESTS))
                .map(MongoReadModel::leaveRequest);
    }

    @Override
    public List<LedgerEntryRecorded> ledgerEntries() {
        return mongo.find(new Query().with(Sort.by("_id")), Document.class, LEDGER_ENTRIES).stream()
                .map(MongoReadModel::ledgerEntry).toList();
    }

    @Override
    public Optional<LedgerEntryRecorded> ledgerEntry(long leaveLedgerId) {
        return Optional.ofNullable(mongo.findById(leaveLedgerId, Document.class, LEDGER_ENTRIES))
                .map(MongoReadModel::ledgerEntry);
    }

    @Override
    public Map<Long, ProjectedEmployee> employees(Collection<Long> employeeIds) {
        Map<Long, ProjectedEmployee> found = new HashMap<>();
        if (employeeIds.isEmpty()) {
            return found;
        }
        for (Document document : mongo.find(Query.query(Criteria.where("_id").in(employeeIds)), Document.class,
                EMPLOYEES)) {
            long id = number(document, "_id");
            found.put(id, new ProjectedEmployee(id, document.getString("firstName"), document.getString("lastName"),
                    document.getBoolean("deleted", false)));
        }
        return found;
    }

    @Override
    public Map<Long, String> leaveTypeNames(Collection<Long> leaveTypeIds) {
        Map<Long, String> names = new HashMap<>();
        if (leaveTypeIds.isEmpty()) {
            return names;
        }
        for (Document document : mongo.find(Query.query(Criteria.where("_id").in(leaveTypeIds)),
                Document.class, LEAVE_TYPES)) {
            if (document.getString("leaveTypeName") != null) {
                names.put(number(document, "_id"), document.getString("leaveTypeName"));
            }
        }
        return names;
    }

    @Override
    public Map<BalanceKey, BigDecimal> balances(Set<Long> employeeIds) {
        Map<BalanceKey, BigDecimal> balances = new HashMap<>();
        if (employeeIds.isEmpty()) {
            return balances;
        }
        List<Document> pipeline = List.of(
                new Document("$match", new Document("employeeId", new Document("$in", employeeIds))),
                new Document("$group", new Document("_id", new Document("employeeId", "$employeeId")
                        .append("leaveTypeId", "$leaveTypeId"))
                        .append("balance", new Document("$sum", "$changeAmountDays"))));
        for (Document group : mongo.getCollection(LEDGER_ENTRIES).aggregate(pipeline)) {
            Document key = group.get("_id", Document.class);
            balances.put(new BalanceKey(number(key, "employeeId"), number(key, "leaveTypeId")),
                    decimal(group.get("balance")));
        }
        return balances;
    }

    // ---- document <-> record ------------------------------------------------------------------------------------

    private static LeaveTypeSnapshot leaveType(Document d) {
        return new LeaveTypeSnapshot(number(d, "_id"), d.getString("leaveTypeName"),
                d.getString("leaveTypeDescription"), d.getBoolean("requiresApproval", false),
                d.getBoolean("isPaidLeave", false));
    }

    private static LeaveRequestSnapshot leaveRequest(Document d) {
        Document decision = d.get("decision", Document.class);
        return new LeaveRequestSnapshot(number(d, "_id"), number(d, "employeeId"), number(d, "leaveTypeId"),
                d.getString("leaveTypeName"), LocalDate.parse(d.getString("startDate")),
                LocalDate.parse(d.getString("endDate")), d.getString("requestStatus"), d.getString("reason"),
                instant(d.getString("requestedDatetime")),
                decision == null ? null : new LeaveDecision(number(decision, "approverEmployeeId"),
                        ApprovalDecision.valueOf(decision.getString("decision")), decision.getString("comment"),
                        instant(decision.getString("decisionDatetime")),
                        decision.get("leaveApprovalId") == null ? null : number(decision, "leaveApprovalId")),
                d.getString("rejectionReason"));
    }

    private static LedgerEntryRecorded ledgerEntry(Document d) {
        return new LedgerEntryRecorded(number(d, "_id"), number(d, "employeeId"), number(d, "leaveTypeId"),
                decimal(d.get("changeAmountDays")),
                d.get("balanceAfterDays") == null ? null : decimal(d.get("balanceAfterDays")),
                d.getString("transactionType"), d.getString("referenceEntityType"),
                d.get("referenceEntityId") == null ? null : number(d, "referenceEntityId"),
                instant(d.getString("transactionDatetime")));
    }

    private static long number(Document d, String field) {
        return ((Number) d.get(field)).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof Decimal128 decimal ? decimal.bigDecimalValue() : new BigDecimal(value.toString());
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
