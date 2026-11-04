package dk.ek.shift_happens.identity.domain;

import java.time.Instant;
import java.util.Optional;

/** Port: started third-party logins, shared by every replica. */
public interface LoginAttemptRepository {

    void save(LoginAttempt attempt);

    /** Removes and returns the attempt in one atomic step, so two callbacks can never both use a state. */
    Optional<LoginAttempt> take(String state);

    /** Housekeeping: attempts that were started but never finished. */
    void deleteExpired(Instant now);
}
