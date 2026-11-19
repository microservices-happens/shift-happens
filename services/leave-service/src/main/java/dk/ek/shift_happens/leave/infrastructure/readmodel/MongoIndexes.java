package dk.ek.shift_happens.leave.infrastructure.readmodel;

import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the read model's indexes at startup (auto-index-creation is off, so nothing depends on entity annotations).
 * {@code createIndex} is idempotent, so every start and every replica may run it.
 */
@Component
@Profile("query")
class MongoIndexes {

    private final MongoTemplate mongo;

    MongoIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @PostConstruct
    void create() {
        index(MongoReadModel.LEAVE_REQUESTS, "employeeId");
        index(MongoReadModel.LEAVE_REQUESTS, "requestStatus");
        index(MongoReadModel.LEAVE_REQUESTS, "leaveTypeId");
        index(MongoReadModel.LEAVE_REQUESTS, "decision.leaveApprovalId");
        // The balance aggregation matches on the employee and groups per leave type.
        mongo.getCollection(MongoReadModel.LEDGER_ENTRIES)
                .createIndex(new Document("employeeId", 1).append("leaveTypeId", 1));
    }

    private void index(String collection, String field) {
        mongo.getCollection(collection).createIndex(new Document(field, 1));
    }
}
