package dk.ek.shift_happens.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface AccountJpaRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByEmployeeId(long employeeId);

    Optional<AccountEntity> findByEmailIgnoreCase(String email);

    // flushAutomatically: a row inserted earlier in the same transaction must exist before the UPDATE.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AccountEntity a SET a.passwordHash = :hash WHERE a.employeeId = :employeeId")
    int updatePasswordHash(@Param("employeeId") long employeeId, @Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AccountEntity a SET a.passwordHash = :hash WHERE a.employeeId = :employeeId AND a.passwordHash IS NULL")
    int updatePasswordHashIfNull(@Param("employeeId") long employeeId, @Param("hash") String hash);
}
