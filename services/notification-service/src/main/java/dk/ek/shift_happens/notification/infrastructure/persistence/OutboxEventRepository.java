
package dk.ek.shift_happens.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEventEntity, UUID> {

    @Query("""
        SELECT e
        FROM OutboxEventEntity e
        WHERE e.publishedAt IS NULL
        ORDER BY e.occurredAt ASC
    """)
    List<OutboxEventEntity> findUnpublishedEvents();
}
