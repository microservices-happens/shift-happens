package dk.ek.shift_happens.scheduling.infrastructure.readmodel;

import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.ReadModelProjection;
import dk.ek.shift_happens.scheduling.domain.ReadModelQueries;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import dk.ek.shift_happens.scheduling.domain.SwapView;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The query role's read model (P2.10): Mongo documents shaped for the GraphQL schedule view.
 *
 * <p>No JPA and no Postgres here, which is why the query deployment keeps answering while the command
 * role is down.
 *
 * <p>Ordering is never assumed. Every write compares the incoming {@code aggregateVersion} with the
 * stored one, keeps the highest, and never lets a snapshot clear a tombstone. The suggestion is the one
 * exception: it is keyed by shift and job role and the newest {@code suggestedAt} wins, because the AI
 * event carries the shift id, not the shift version.
 */
@Repository
@Profile("query")
class MongoReadModel implements ReadModelProjection, ReadModelQueries {

    static final String SHIFTS = "shifts";
    static final String ASSIGNMENTS = "assignments";
    static final String EMPLOYEES = "employees";
    static final String SWAPS = "swaps";
    static final String SUGGESTIONS = "suggestions";

    private static final Logger log = LoggerFactory.getLogger(MongoReadModel.class);

    private final MongoTemplate mongo;

    MongoReadModel(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** The reads are always one window or one employee, so those are the indexes worth having. */
    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(SHIFTS).ensureIndex(new Index().on("startDatetime", Sort.Direction.ASC)
                .on("endDatetime", Sort.Direction.ASC));
        mongo.indexOps(SHIFTS).ensureIndex(new Index().on("departmentId", Sort.Direction.ASC));
        mongo.indexOps(ASSIGNMENTS).ensureIndex(new Index().on("shiftId", Sort.Direction.ASC));
        mongo.indexOps(ASSIGNMENTS).ensureIndex(new Index().on("employeeId", Sort.Direction.ASC)
                .on("shiftStartDatetime", Sort.Direction.ASC));
        mongo.indexOps(SWAPS).ensureIndex(new Index().on("employeeFromId", Sort.Direction.ASC));
        mongo.indexOps(SWAPS).ensureIndex(new Index().on("employeeToId", Sort.Direction.ASC));
    }

    @Override
    public void upsertShift(ShiftView shift) {
        upsert(SHIFTS, ReadModelDocuments.of(shift));
    }

    @Override
    public void deleteShift(long shiftId, long aggregateVersion, Instant deletedAt) {
        findShift(shiftId).ifPresent(current ->
                upsert(SHIFTS, ReadModelDocuments.of(current.tombstoned(aggregateVersion, deletedAt))));
    }

    @Override
    public void upsertAssignment(AssignmentView assignment) {
        upsert(ASSIGNMENTS, ReadModelDocuments.of(assignment));
    }

    @Override
    public void deleteAssignment(long shiftAssignmentId, long aggregateVersion, Instant deletedAt) {
        findAssignment(shiftAssignmentId).ifPresent(current -> upsert(ASSIGNMENTS,
                ReadModelDocuments.of(current.tombstoned(aggregateVersion, deletedAt))));
    }

    @Override
    public void upsertEmployee(EmployeeView employee) {
        upsert(EMPLOYEES, ReadModelDocuments.of(employee));
    }

    @Override
    public void deleteEmployee(long employeeId, long aggregateVersion, Instant deletedAt) {
        findEmployee(employeeId).ifPresent(current ->
                upsert(EMPLOYEES, ReadModelDocuments.of(current.tombstoned(aggregateVersion, deletedAt))));
    }

    @Override
    public void upsertSwap(SwapView swap) {
        upsert(SWAPS, ReadModelDocuments.of(swap));
    }

    @Override
    public void upsertSuggestion(SuggestionView suggestion) {
        String id = suggestion.shiftId() + ":" + suggestion.jobRoleId();
        Document current = mongo.findById(id, Document.class, SUGGESTIONS);
        if (current != null
                && !suggestion.suggestedAt().isAfter(ReadModelDocuments.instantOf(current, "suggestedAt"))) {
            log.debug("Ignoring suggestion {} from {}: not newer than the stored one", id,
                    suggestion.suggestedAt());
            return;
        }
        mongo.save(ReadModelDocuments.suggestion(id, suggestion), SUGGESTIONS);
    }

    @Override
    public void clear() {
        for (String collection : List.of(SHIFTS, ASSIGNMENTS, EMPLOYEES, SWAPS, SUGGESTIONS)) {
            mongo.remove(new Query(), collection);
        }
    }

    /**
     * The one write rule of the projection: the highest aggregateVersion wins, and an existing tombstone
     * is never cleared. An equal or lower version is a no-op, so a redelivered event changes nothing.
     */
    private void upsert(String collection, Document document) {
        long version = ReadModelDocuments.longOf(document, "aggregateVersion");
        boolean tombstone = document.get("deletedAt") != null;
        Document current = mongo.findById(document.get("_id"), Document.class, collection);
        if (current != null) {
            if (version <= ReadModelDocuments.longOf(current, "aggregateVersion")) {
                log.debug("Ignoring {}/{} v{}: stored version is {}", collection, document.get("_id"), version,
                        ReadModelDocuments.longOf(current, "aggregateVersion"));
                return;
            }
            if (current.get("deletedAt") != null && !tombstone) {
                log.debug("Ignoring {}/{} v{}: the entity is tombstoned", collection, document.get("_id"),
                        version);
                return;
            }
        }
        mongo.save(document, collection);
    }

    @Override
    public Optional<ShiftView> findShift(long shiftId) {
        Document document = mongo.findById(shiftId, Document.class, SHIFTS);
        return Optional.ofNullable(document).map(ReadModelDocuments::toShiftView);
    }

    @Override
    public List<ShiftView> findShifts(Instant from, Instant to, Long departmentId, Long workLocationId,
                                      ShiftStatus status) {
        Criteria criteria = Criteria.where("deletedAt").is(null)
                .and("startDatetime").lt(ReadModelDocuments.date(to))
                .and("endDatetime").gt(ReadModelDocuments.date(from));
        if (departmentId != null) {
            criteria = criteria.and("departmentId").is(departmentId);
        }
        if (workLocationId != null) {
            criteria = criteria.and("workLocationId").is(workLocationId);
        }
        if (status != null) {
            criteria = criteria.and("status").is(status.name());
        }
        Query query = new Query(criteria).with(Sort.by("startDatetime", "_id"));
        return mongo.find(query, Document.class, SHIFTS).stream().map(ReadModelDocuments::toShiftView).toList();
    }

    @Override
    public List<ShiftView> findShiftsForEmployee(long employeeId, Instant from, Instant to) {
        List<Long> shiftIds = findAssignmentsOfEmployee(employeeId, from, to).stream()
                .map(AssignmentView::shiftId).distinct().toList();
        if (shiftIds.isEmpty()) {
            return List.of();
        }
        Criteria criteria = Criteria.where("_id").in(shiftIds)
                .and("deletedAt").is(null)
                .and("startDatetime").lt(ReadModelDocuments.date(to))
                .and("endDatetime").gt(ReadModelDocuments.date(from));
        Query query = new Query(criteria).with(Sort.by("startDatetime", "_id"));
        return mongo.find(query, Document.class, SHIFTS).stream().map(ReadModelDocuments::toShiftView).toList();
    }

    @Override
    public List<AssignmentView> findAssignmentsOfShift(long shiftId) {
        Query query = new Query(Criteria.where("shiftId").is(shiftId).and("deletedAt").is(null))
                .with(Sort.by("_id"));
        return mongo.find(query, Document.class, ASSIGNMENTS).stream()
                .map(ReadModelDocuments::toAssignmentView).toList();
    }

    @Override
    public List<AssignmentView> findAssignmentsOfEmployee(long employeeId, Instant from, Instant to) {
        Criteria criteria = Criteria.where("employeeId").is(employeeId)
                .and("deletedAt").is(null)
                .and("shiftStartDatetime").lt(ReadModelDocuments.date(to))
                .and("shiftEndDatetime").gt(ReadModelDocuments.date(from));
        Query query = new Query(criteria).with(Sort.by("shiftStartDatetime", "_id"));
        return mongo.find(query, Document.class, ASSIGNMENTS).stream()
                .map(ReadModelDocuments::toAssignmentView).toList();
    }

    @Override
    public Optional<AssignmentView> findAssignment(long shiftAssignmentId) {
        Document document = mongo.findById(shiftAssignmentId, Document.class, ASSIGNMENTS);
        return Optional.ofNullable(document).map(ReadModelDocuments::toAssignmentView);
    }

    @Override
    public Optional<EmployeeView> findEmployee(long employeeId) {
        Document document = mongo.findById(employeeId, Document.class, EMPLOYEES);
        return Optional.ofNullable(document).map(ReadModelDocuments::toEmployeeView);
    }

    @Override
    public List<EmployeeView> findEmployees(Collection<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("_id").in(employeeIds));
        return mongo.find(query, Document.class, EMPLOYEES).stream()
                .map(ReadModelDocuments::toEmployeeView).toList();
    }

    @Override
    public List<SwapView> findSwaps(SwapStatus status) {
        Query query = new Query();
        if (status != null) {
            query.addCriteria(Criteria.where("swapStatus").is(status.name()));
        }
        query.with(Sort.by(Sort.Direction.DESC, "requestDatetime"));
        return mongo.find(query, Document.class, SWAPS).stream()
                .map(ReadModelDocuments::toSwapView).toList();
    }

    @Override
    public List<SwapView> findSwapsOfEmployee(long employeeId) {
        Criteria criteria = new Criteria().orOperator(
                Criteria.where("employeeFromId").is(employeeId),
                Criteria.where("employeeToId").is(employeeId));
        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "requestDatetime"));
        return mongo.find(query, Document.class, SWAPS).stream()
                .map(ReadModelDocuments::toSwapView).toList();
    }

    @Override
    public List<SuggestionView> findSuggestions(long shiftId) {
        Query query = new Query(Criteria.where("shiftId").is(shiftId)).with(Sort.by("jobRoleId"));
        return mongo.find(query, Document.class, SUGGESTIONS).stream()
                .map(ReadModelDocuments::toSuggestionView).toList();
    }
}
