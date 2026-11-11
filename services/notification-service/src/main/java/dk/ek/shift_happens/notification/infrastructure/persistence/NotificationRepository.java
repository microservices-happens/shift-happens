
package dk.ek.shift_happens.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    List<NotificationEntity> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    List<NotificationEntity> findByEmployeeIdAndReadFalseOrderByCreatedAtDesc(Long employeeId);

    boolean existsBySourceEventIdAndEmployeeId(
        UUID sourceEventId,
        Long employeeId
    );

    @Modifying
    @Query(value = """
        INSERT INTO notification (
            employee_id,
            title,
            message,
            source_event_type,
            source_event_id
        )
        VALUES (
            :employeeId,
            :title,
            :message,
            :sourceEventType,
            :sourceEventId
        )
        ON CONFLICT (source_event_id, employee_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("employeeId") Long employeeId,
            @Param("title") String title,
            @Param("message") String message,
            @Param("sourceEventType") String sourceEventType,
            @Param("sourceEventId") UUID sourceEventId
    );
}
