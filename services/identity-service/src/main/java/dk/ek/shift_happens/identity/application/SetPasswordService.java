package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.Caller;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PUT /v1/auth/accounts/{employeeId}/password.
 *
 * <p>A deactivated account can still get a password: it just cannot log in until workforce
 * reactivates it, so an admin can prepare the account before that.
 */
@Service
public class SetPasswordService {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public SetPasswordService(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void setPassword(Caller caller, long employeeId, String rawPassword) {
        // Checked before the lookup, so a non-admin cannot probe which employee ids have accounts.
        if (!caller.maySetPasswordOf(employeeId)) {
            throw new NotAllowedException("Only an Administrator can set another employee's password");
        }
        if (!accounts.setPasswordHash(employeeId, encode(rawPassword))) {
            throw new AccountNotFoundException(employeeId);
        }
    }

    private String encode(String rawPassword) {
        try {
            return passwordEncoder.encode(rawPassword);
        } catch (IllegalArgumentException e) {
            // bcrypt takes at most 72 bytes (pepper included) and refuses longer input.
            throw new InvalidPasswordException("Password is too long");
        }
    }
}
