package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Local login (POST /v1/auth/login): checks the password and issues a JWT. */
@Service
public class LoginService {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    // Compared against when the email is unknown, so a miss takes as long as a wrong password.
    private final String dummyHash;

    public LoginService(AccountRepository accounts, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public record LoginResult(String token, Account account) {
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        Optional<Account> found = accounts.findByEmail(email);

        if (found.isEmpty() || !found.get().canLogInWithPassword()) {
            passwordEncoder.matches(password, dummyHash);
            throw new InvalidCredentialsException();
        }

        Account account = found.get();
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return new LoginResult(tokenIssuer.issueFor(account), account);
    }
}
