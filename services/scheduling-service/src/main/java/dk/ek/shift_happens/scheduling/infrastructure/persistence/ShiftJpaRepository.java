package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the shift table. Only the adapter in this package uses it. */
interface ShiftJpaRepository extends JpaRepository<ShiftEntity, Long> {
}
