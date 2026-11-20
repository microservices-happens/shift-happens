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
import java.util.stream.Stream;

/**
 * The query role's read model (P2.10): Mongo documents shaped for the GraphQL schedule view.
 *
 * <p>No JPA and no Postgres here, which is why the query deployment keeps answering while the command
 * role is down.
 *
 * <p>Ordering is never assumed (P3.3). Every document carries two versions: {@code snapshotVersion} is
 * the version of the snapshot its fields come from (0 while only a tombstone has arrived) and
 * {@code aggregateVersion} is the highest version seen, snapshot or tombstone. A snapshot replaces the
 * fields of an older snapshot only and never clears a tombstone; a tombstone is stored even when the
 * create has not arrived yet. The same events therefore give the same documents in any order
 * ({@code SchedulingProjectionShuffledOrderIT}). The suggestion is the one exception: it is keyed by
 * shift and job role and the newest {@code suggestedAt} wins, because the AI event carries the shift
 * id, not the shift version.
 */
@Repository
@Profile("query")
class MongoReadModel implements ReadModelProjection, ReadModelQueries {

    static final String SHIFTS = "shifts";
    static final String ASSIGNMENTS = "assignments";
    static final String EMPLOYEES = "employees";
    static final String SWAPS = "swaps";
    static final String SUGGESTIONS = "suggestions";

    private static final String AGGREGATE_VERSION = "aggregateVersion";
    private static final String SNAPSHOT_VERSION = "snapshotVersion";
    private static final String DELETED_AT = "deletedAt";

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
        tombstone(SHIFTS, shiftId, aggregateVersion, deletedAt);
    }

    @Override
    public void upsertAssignment(AssignmentView assignment) {
        upsert(ASSIGNMENTS, ReadModelDocuments.of(assignment));
    }

    @Override
    public void deleteAssignment(long shiftAssignmentId, long aggregateVersion, Instant deletedAt) {
        tombstone(ASSIGNMENTS, shiftAssignmentId, aggregateVersion, deletedAt);
    }

    @Override
    public void upsertEmployee(EmployeeView employee) {
        upsert(EMPLOYEES, ReadModelDocuments.of(employee));
    }

    @Override
    public void deleteEmployee(long employeeId, long aggregateVersion, Instant deletedAt) {
        tombstone(EMPLOYEES, employeeId, aggregateVersion, deletedAt);
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
     * The snapshot rule of the projection. The fields are replaced only by a newer snapshot than the one
     * they come from, so an equal or lower version is a no-op and a redelivered event changes nothing.
     *
     * <p>On a tombstoned document that still holds for a snapshot older than the tombstone: it was
     * published before the delete and overtaken by it, and applying it is what makes the document end
     * with the last snapshot before the delete in any order. A snapshot that is not older than the
     * tombstone is ignored. The tombstone itself and the highest version are always carried over.
     */
    private void upsert(String collection, Document snapshot) {
        long version = ReadModelDocuments.longOf(snapshot, AGGREGATE_VERSION);
        Document current = mongo.findById(snapshot.get("_id"), Document.class, collection);
        if (current == null) {
            mongo.save(snapshot.append(SNAPSHOT_VERSION, version), collection);
            return;
        }
        long storedVersion = ReadModelDocuments.longOf(current, AGGREGATE_VERSION);
        if (version <= snapshotVersionOf(current)) {
            log.debug("Ignoring {}/{} v{}: the stored snapshot is v{}", collection, snapshot.get("_id"), version,
                    snapshotVersionOf(current));
            return;
        }
        if (current.get(DELETED_AT) != null && version >= storedVersion) {
            log.debug("Ignoring {}/{} v{}: the entity is tombstoned", collection, snapshot.get("_id"), version);
            return;
        }
        snapshot.put(AGGREGATE_VERSION, Math.max(storedVersion, version));
        snapshot.put(SNAPSHOT_VERSION, version);
        if (current.get(DELETED_AT) != null) {
            snapshot.put(DELETED_AT, current.get(DELETED_AT));
        }
        mongo.save(snapshot, collection);
    }

    /**
     * The tombstone rule of the projection: the document is kept, marked deleted and moved to the highest
     * version. When the delete overtook the create there is no document yet; a tombstone without fields
     * ({@code snapshotVersion} 0) is stored, so the late create cannot resurrect the entity.
     */
    private void tombstone(String collection, long id, long version, Instant deletedAt) {
        Document current = mongo.findById(id, Document.class, collection);
        if (current == null) {
            mongo.save(new Document("_id", id)
                    .append(AGGREGATE_VERSION, version)
                    .append(SNAPSHOT_VERSION, 0L)
                    .append(DELETED_AT, ReadModelDocuments.date(deletedAt)), collection);
            return;
        }
        long storedVersion = ReadModelDocuments.longOf(current, AGGREGATE_VERSION);
        if (current.get(DELETED_AT) != null && version <= storedVersion) {
            log.debug("Ignoring tombstone {}/{} v{}: already tombstoned at v{}", collection, id, version,
                    storedVersion);
            return;
        }
        // Written before snapshotVersion existed: the fields belong to the version the document held.
        current.put(SNAPSHOT_VERSION, snapshotVersionOf(current));
        current.put(AGGREGATE_VERSION, Math.max(storedVersion, version));
        if (current.get(DELETED_AT) == null) {
            current.put(DELETED_AT, ReadModelDocuments.date(deletedAt));
        }
        mongo.save(current, collection);
    }

    /** Documents written before P3.3 have no snapshotVersion: their fields came with their version. */
    private static long snapshotVersionOf(Document document) {
        return document.get(SNAPSHOT_VERSION) == null ? ReadModelDocuments.longOf(document, AGGREGATE_VERSION)
                : ReadModelDocuments.longOf(document, SNAPSHOT_VERSION);
    }

    /** A tombstone that arrived before any snapshot has no fields to show: it is not a view yet. */
    private static boolean hasSnapshot(Document document) {
        return snapshotVersionOf(document) > 0;
    }

    @Override
    public Optional<ShiftView> findShift(long shiftId) {
        Document document = mongo.findById(shiftId, Document.class, SHIFTS);
        return Optional.ofNullable(document).filter(MongoReadModel::hasSnapshot)
                .map(ReadModelDocuments::toShiftView);
    }

    @Override
    public List<ShiftView> findShiftsByIds(Collection<Long> shiftIds) {
        return findByIds(SHIFTS, shiftIds).map(ReadModelDocuments::toShiftView).toList();
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
    public List<AssignmentView> findAssignmentsOfShifts(Collection<Long> shiftIds) {
        if (shiftIds == null || shiftIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("shiftId").in(shiftIds).and("deletedAt").is(null))
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
        return Optional.ofNullable(document).filter(MongoReadModel::hasSnapshot)
                .map(ReadModelDocuments::toAssignmentView);
    }

    @Override
    public List<AssignmentView> findAssignmentsByIds(Collection<Long> shiftAssignmentIds) {
        return findByIds(ASSIGNMENTS, shiftAssignmentIds).map(ReadModelDocuments::toAssignmentView).toList();
    }

    @Override
    public Optional<EmployeeView> findEmployee(long employeeId) {
        Document document = mongo.findById(employeeId, Document.class, EMPLOYEES);
        return Optional.ofNullable(document).filter(MongoReadModel::hasSnapshot)
                .map(ReadModelDocuments::toEmployeeView);
    }

    @Override
    public List<EmployeeView> findEmployees(Collection<Long> employeeIds) {
        return findByIds(EMPLOYEES, employeeIds).map(ReadModelDocuments::toEmployeeView).toList();
    }

    /**
     * The batch form of the reads by id: one query, and like them a tombstone that arrived before
     * any snapshot is not a view. No ids, no query. (One query is one find command; the driver fetches a
     * result of more than 101 documents in further cursor batches.)
     */
    private Stream<Document> findByIds(String collection, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Stream.empty();
        }
        return mongo.find(new Query(Criteria.where("_id").in(ids)), Document.class, collection).stream()
                .filter(MongoReadModel::hasSnapshot);
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

    @Override
    public List<SuggestionView> findSuggestionsOfShifts(Collection<Long> shiftIds) {
        if (shiftIds == null || shiftIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("shiftId").in(shiftIds)).with(Sort.by("shiftId", "jobRoleId"));
        return mongo.find(query, Document.class, SUGGESTIONS).stream()
                .map(ReadModelDocuments::toSuggestionView).toList();
    }
}
