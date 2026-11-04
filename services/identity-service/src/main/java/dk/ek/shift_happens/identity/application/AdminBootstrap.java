package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-admin bootstrap. Accounts only come from workforce events and start without a password, so
 * without this nobody could ever log in to set one. With BOOTSTRAP_ADMIN_EMAIL and
 * BOOTSTRAP_ADMIN_PASSWORD set, the Administrator account with that email gets that password once.
 *
 * <p>It never overwrites a password (a changed admin password is not reset on the next restart or
 * event) and never applies to a non-Administrator (the variables cannot hand a login to someone else).
 * Both variables empty = off.
 */
@Service
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminBootstrap(AccountRepository accounts, PasswordEncoder passwordEncoder,
                          @Value("${app.bootstrap-admin.email}") String email,
                          @Value("${app.bootstrap-admin.password}") String password) {
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasPassword = password != null && !password.isEmpty();
        if (hasEmail != hasPassword) {
            throw new IllegalStateException("Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD, or neither");
        }
        if (hasPassword && password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be at least 8 characters");
        }
        if (hasPassword) {
            try {
                passwordEncoder.encode(password);
            } catch (IllegalArgumentException e) {
                // bcrypt takes at most 72 bytes, pepper included. Fail now, not later inside the
                // projection, where the admin's workforce event would be dead-lettered.
                throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD is too long for bcrypt with this pepper");
            }
        }
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.email = hasEmail ? email.trim() : null;
        this.password = hasPassword ? password : null;
    }

    /** The account to save: with the bootstrap password if the rule applies, otherwise unchanged. */
    public Account apply(Account account) {
        if (email == null || !email.equalsIgnoreCase(account.email()) || account.passwordHash() != null) {
            return account;
        }
        if (!Account.ADMINISTRATOR_ROLE.equals(account.role())) {
            log.warn("BOOTSTRAP_ADMIN_EMAIL matches employee {} with role {}, not Administrator: no password set",
                    account.employeeId(), account.role());
            return account;
        }
        log.info("Bootstrap: setting the first password of Administrator employee {}", account.employeeId());
        return account.withPasswordHash(passwordEncoder.encode(password));
    }

    /**
     * Stores the bootstrap password for a saved account if the rule applies. Only the hash column is
     * written, and only while it is still empty, so a concurrent password change always wins.
     */
    public void applyTo(Account saved) {
        Account result = apply(saved);
        if (result != saved) {
            accounts.setPasswordHashIfUnset(saved.employeeId(), result.passwordHash());
        }
    }

    /** For an admin account that arrived before the variables were set; later ones go through the projection. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void applyToExistingAccount() {
        if (email == null) {
            return;
        }
        accounts.findByEmail(email).ifPresent(this::applyTo);
    }
}
