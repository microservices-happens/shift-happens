package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for the outbox. The relay asks for the oldest unpublished events. */
interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findTop50ByPublishedAtIsNullOrderByOccurredAtAsc();
}
