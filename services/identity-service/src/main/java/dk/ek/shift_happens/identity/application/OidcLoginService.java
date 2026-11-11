package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.ExternalIdentity;
import dk.ek.shift_happens.identity.domain.ExternalIdentityLinkRepository;
import dk.ek.shift_happens.identity.domain.LoginAttempt;
import dk.ek.shift_happens.identity.domain.LoginAttemptRepository;
import dk.ek.shift_happens.identity.domain.OidcProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Google/GitHub login (P2.3): OAuth 2.0 authorization code + PKCE, then the provider's verified email
 * must belong to an active account. Accounts are never created here; they only come from workforce.
 *
 * <p>Matching: a provider account that has logged in before is found by its link (provider + subject),
 * so a changed email on either side does not lock the employee out. The link wins over the email.
 * The first login links by verified email.
 */
public class OidcLoginService {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);

    private final List<OidcProviderClient> clients;
    private final LoginAttemptRepository attempts;
    private final ExternalIdentityLinkRepository links;
    private final AccountRepository accounts;
    private final TokenIssuer tokenIssuer;
    private final String redirectBaseUrl;
    private final Duration stateTtl;
    private final Clock clock;

    public OidcLoginService(List<OidcProviderClient> clients, LoginAttemptRepository attempts,
                            ExternalIdentityLinkRepository links, AccountRepository accounts,
                            TokenIssuer tokenIssuer, String redirectBaseUrl, Duration stateTtl, Clock clock) {
        this.clients = List.copyOf(clients);
        this.attempts = attempts;
        this.links = links;
        this.accounts = accounts;
        this.tokenIssuer = tokenIssuer;
        this.redirectBaseUrl = redirectBaseUrl;
        this.stateTtl = stateTtl;
        this.clock = clock;
    }

    /** Starts a login: stores a single-use state + PKCE verifier and returns the provider's URL. */
    public URI authorize(String providerId) {
        OidcProviderClient client = enabledClient(providerId).orElseThrow(OidcProviderUnavailableException::new);
        Instant now = clock.instant();
        attempts.deleteExpired(now);
        LoginAttempt attempt = LoginAttempt.start(client.provider(), now, stateTtl);
        attempts.save(attempt);
        return client.authorizationUri(attempt.state(), attempt.codeChallenge(), redirectUri(client.provider()));
    }

    /**
     * Finishes a login and returns the Shift Happens JWT.
     *
     * @param providerError the provider's {@code error} parameter (e.g. the user pressed cancel), or null
     * @throws OidcLoginException with the code for the frontend when the login is refused
     */
    public String complete(String providerId, String code, String state, String providerError) {
        OidcProviderClient client = enabledClient(providerId)
                .orElseThrow(() -> new OidcLoginException(OidcLoginException.UNKNOWN_PROVIDER));
        OidcProvider provider = client.provider();

        // The state is consumed before anything else, so it cannot be tried a second time.
        LoginAttempt attempt = (state == null || state.isBlank() ? Optional.<LoginAttempt>empty() : attempts.take(state))
                .filter(found -> found.isFor(provider))
                .orElseThrow(() -> refused(provider, OidcLoginException.INVALID_STATE));
        if (attempt.isExpiredAt(clock.instant())) {
            throw refused(provider, OidcLoginException.EXPIRED_STATE);
        }
        if (providerError != null) {
            throw refused(provider, OidcLoginException.ACCESS_DENIED);
        }
        if (code == null || code.isBlank()) {
            throw refused(provider, OidcLoginException.INVALID_REQUEST);
        }

        ExternalIdentity identity;
        try {
            identity = client.exchange(code, attempt.codeVerifier(), redirectUri(provider));
        } catch (OidcProviderException e) {
            log.warn("{} login failed at the provider: {}", provider.id(), e.getMessage());
            throw new OidcLoginException(OidcLoginException.PROVIDER_ERROR);
        }
        if (!identity.emailVerified() || identity.email() == null) {
            throw refused(provider, OidcLoginException.EMAIL_NOT_VERIFIED);
        }

        Account account = match(identity)
                .filter(Account::canLogInWithProvider)
                .orElseThrow(() -> refused(provider, OidcLoginException.NO_ACTIVE_ACCOUNT));
        links.link(provider, identity.subject(), account.employeeId());
        log.info("{} login for employee {}", provider.id(), account.employeeId());
        return tokenIssuer.issueFor(account);
    }

    private Optional<Account> match(ExternalIdentity identity) {
        Optional<Long> linkedEmployee = links.findEmployeeId(identity.provider(), identity.subject());
        if (linkedEmployee.isPresent()) {
            return accounts.findByEmployeeId(linkedEmployee.get());
        }
        return accounts.findByEmail(identity.email());
    }

    private Optional<OidcProviderClient> enabledClient(String providerId) {
        return OidcProvider.fromId(providerId).flatMap(provider -> clients.stream()
                .filter(client -> client.provider() == provider && client.enabled())
                .findFirst());
    }

    private String redirectUri(OidcProvider provider) {
        return redirectBaseUrl + "/" + provider.id() + "/callback";
    }

    private static OidcLoginException refused(OidcProvider provider, String code) {
        log.info("{} login refused: {}", provider.id(), code);
        return new OidcLoginException(code);
    }
}
