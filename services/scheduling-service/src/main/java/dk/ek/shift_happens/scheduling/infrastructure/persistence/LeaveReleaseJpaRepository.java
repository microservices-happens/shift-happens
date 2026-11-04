package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for leave_release. */
interface LeaveReleaseJpaRepository extends JpaRepository<LeaveReleaseEntity, Long> {
}
