package dk.ek.shift_happens.scheduling.infrastructure.readmodel;

import dk.ek.shift_happens.scheduling.domain.ProjectionEvent;
import dk.ek.shift_happens.scheduling.domain.ProjectionEventStore;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The projection log and the processed-event markers of the query role, both in the read database.
 *
 * <p>RabbitMQ keeps no history, so the log is the only complete record of the events the read model was
 * built from. The rebuild replays it, which is why it is never cleared, while the markers are.
 */
@Repository
@Profile("query")
class MongoProjectionEventStore implements ProjectionEventStore {

    static final String PROCESSED = "processed_events";
    static final String EVENTS = "projection_events";

    private final MongoTemplate mongo;

    MongoProjectionEventStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean isProcessed(UUID eventId) {
        return mongo.exists(new Query(Criteria.where("_id").is(eventId.toString())), PROCESSED);
    }

    @Override
    public void markProcessed(UUID eventId) {
        mongo.save(new Document("_id", eventId.toString()).append("processedAt", new Date()), PROCESSED);
    }

    @Override
    public void append(ProjectionEvent event) {
        mongo.save(new Document("_id", event.eventId().toString())
                .append("eventType", event.eventType())
                .append("producer", event.producer())
                .append("aggregateId", event.aggregateId())
                .append("aggregateVersion", event.aggregateVersion())
                .append("occurredAt", ReadModelDocuments.date(event.occurredAt()))
                .append("correlationId", event.correlationId())
                .append("causationId", event.causationId() == null ? null : event.causationId().toString())
                .append("payload", event.payload()), EVENTS);
    }

    @Override
    public List<ProjectionEvent> replayOrder() {
        Query query = new Query().with(Sort.by(Sort.Order.asc("occurredAt"),
                Sort.Order.asc("aggregateVersion"), Sort.Order.asc("_id")));
        return mongo.find(query, Document.class, EVENTS).stream()
                .map(MongoProjectionEventStore::toEvent).toList();
    }

    @Override
    public void clearProcessed() {
        mongo.remove(new Query(), PROCESSED);
    }

    private static ProjectionEvent toEvent(Document document) {
        String causationId = ReadModelDocuments.stringOf(document, "causationId");
        return new ProjectionEvent(
                UUID.fromString(ReadModelDocuments.stringOf(document, "_id")),
                ReadModelDocuments.stringOf(document, "eventType"),
                ReadModelDocuments.stringOf(document, "producer"),
                ReadModelDocuments.longOf(document, "aggregateId"),
                ReadModelDocuments.longOf(document, "aggregateVersion"),
                ReadModelDocuments.instantOf(document, "occurredAt"),
                ReadModelDocuments.stringOf(document, "correlationId"),
                causationId == null ? null : UUID.fromString(causationId),
                document.get("payload"));
    }
}
