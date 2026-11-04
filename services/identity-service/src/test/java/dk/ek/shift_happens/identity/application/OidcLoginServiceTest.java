package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.ExternalIdentity;
import dk.ek.shift_happens.identity.domain.ExternalIdentityLinkRepository;
import dk.ek.shift_happens.identity.domain.LoginAttempt;
import dk.ek.shift_happens.identity.domain.LoginAttemptRepository;
import dk.ek.shift_happens.identity.domain.OidcProvider;
import dk.ek.shift_happens.identity.domain.Pkce;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who may log in with Google/GitHub and when a callback is refused, without Spring or a database. */
class OidcLoginServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
    private final InMemoryAttempts attempts = new InMemoryAttempts();
    private final InMemoryLinks links = new InMemoryLinks();
    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final FakeProvider google = new FakeProvider(OidcProvider.GOOGLE, true);
    private final FakeProvider github = new FakeProvider(OidcProvider.GITHUB, true);
    private final OidcLoginService service = new OidcLoginService(List.of(google, github), attempts, links, accounts,
            account -> "token-for-" + account.employeeId(), "https://gw.test/api/v1/auth/oidc",
            Duration.ofMinutes(10), clock);

    @Test
    void authorizeSendsTheS256ChallengeOfTheVerifierItKeeps() {
        String state = startGoogleLogin();

        LoginAttempt stored = attempts.byState.get(state);
        assertThat(google.lastChallenge).isEqualTo(Pkce.s256(stored.codeVerifier()));
        assertThat(google.lastRedirectUri).isEqualTo("https://gw.test/api/v1/auth/oidc/google/callback");
    }

    @Test
    void theCodeIsExchangedWithTheVerifierThatBelongsToTheState() {
        // Without the matching verifier the provider refuses the code: that is what PKCE protects.
        account(1, "ada@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "ada@shift.dk", true);
        String state = startGoogleLogin();
        String verifier = attempts.byState.get(state).codeVerifier();

        service.complete("google", "code", state, null);

        assertThat(google.lastVerifier).isEqualTo(verifier);
    }

    @Test
    void aVerifiedEmailOfAnActiveAccountGivesThatAccountsToken() {
        account(1, "ada@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "Ada@Shift.DK", true);

        assertThat(service.complete("google", "code", startGoogleLogin(), null)).isEqualTo("token-for-1");
    }

    @Test
    void aStateWorksOnlyOnce() {
        account(1, "ada@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "ada@shift.dk", true);
        String state = startGoogleLogin();
        service.complete("google", "code", state, null);

        assertRefused(() -> service.complete("google", "code", state, null), OidcLoginException.INVALID_STATE);
    }

    @Test
    void unknownMissingOrForeignStatesAreRefusedBeforeTheProviderIsCalled() {
        assertRefused(() -> service.complete("google", "code", "made-up", null), OidcLoginException.INVALID_STATE);
        assertRefused(() -> service.complete("google", "code", null, null), OidcLoginException.INVALID_STATE);

        String githubState = startLogin("github");
        assertRefused(() -> service.complete("google", "code", githubState, null), OidcLoginException.INVALID_STATE);
        assertThat(google.exchanges).isZero();
    }

    @Test
    void anExpiredStateIsRefused() {
        String state = startGoogleLogin();
        clock.advance(Duration.ofMinutes(11));

        assertRefused(() -> service.complete("google", "code", state, null), OidcLoginException.EXPIRED_STATE);
    }

    @Test
    void anUnverifiedEmailIsNeverMatched() {
        // Otherwise anyone could add a colleague's address to their provider account and log in as them.
        account(1, "ada@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "ada@shift.dk", false);

        assertRefused(() -> service.complete("google", "code", startGoogleLogin(), null),
                OidcLoginException.EMAIL_NOT_VERIFIED);
    }

    @Test
    void unknownAndInactiveAccountsAreRefusedTheSameWay() {
        // Accounts only come from workforce; a provider login never creates one.
        account(2, "former@shift.dk", false);

        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-x", "stranger@gmail.com", true);
        assertRefused(() -> service.complete("google", "code", startGoogleLogin(), null),
                OidcLoginException.NO_ACTIVE_ACCOUNT);

        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-2", "former@shift.dk", true);
        assertRefused(() -> service.complete("google", "code", startGoogleLogin(), null),
                OidcLoginException.NO_ACTIVE_ACCOUNT);
        assertThat(accounts.byEmployeeId).hasSize(1);
    }

    @Test
    void aLinkedProviderAccountKeepsWorkingAfterAnEmailChange() {
        account(1, "ada@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "ada@shift.dk", true);
        service.complete("google", "code", startGoogleLogin(), null);

        // Workforce renames the employee's email; Google still reports the old one.
        accounts.save(new Account(1L, 1, "EMP-1", "ada.lovelace@shift.dk", "Ada", "L", "Employee", true, 2, null));

        assertThat(service.complete("google", "code", startGoogleLogin(), null)).isEqualTo("token-for-1");
    }

    @Test
    void theLinkWinsOverAnEmailThatNowPointsAtSomeoneElse() {
        account(1, "ada@shift.dk", true);
        account(2, "grace@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "ada@shift.dk", true);
        service.complete("google", "code", startGoogleLogin(), null);

        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "grace@shift.dk", true);

        assertThat(service.complete("google", "code", startGoogleLogin(), null)).isEqualTo("token-for-1");
    }

    @Test
    void aLinkToADeactivatedEmployeeIsRefused() {
        account(1, "ada@shift.dk", true);
        google.identity = new ExternalIdentity(OidcProvider.GOOGLE, "g-1", "ada@shift.dk", true);
        service.complete("google", "code", startGoogleLogin(), null);
        accounts.save(accounts.byEmployeeId.get(1L).deleted(2));

        assertRefused(() -> service.complete("google", "code", startGoogleLogin(), null),
                OidcLoginException.NO_ACTIVE_ACCOUNT);
    }

    @Test
    void providerFailuresAndCancelledConsentEndInAnErrorCode() {
        google.failure = new OidcProviderException("token endpoint said no");
        assertRefused(() -> service.complete("google", "code", startGoogleLogin(), null),
                OidcLoginException.PROVIDER_ERROR);

        assertRefused(() -> service.complete("google", null, startGoogleLogin(), "access_denied"),
                OidcLoginException.ACCESS_DENIED);
    }

    @Test
    void aDisabledOrUnknownProviderCannotStartOrFinishALogin() {
        github.enabled = false;

        assertThatThrownBy(() -> service.authorize("github")).isInstanceOf(OidcProviderUnavailableException.class);
        assertThatThrownBy(() -> service.authorize("facebook")).isInstanceOf(OidcProviderUnavailableException.class);
        assertRefused(() -> service.complete("github", "code", "s", null), OidcLoginException.UNKNOWN_PROVIDER);
    }

    @Test
    void startingALoginCleansUpExpiredAttempts() {
        String abandoned = startGoogleLogin();
        clock.advance(Duration.ofMinutes(11));

        startGoogleLogin();

        assertThat(attempts.byState).doesNotContainKey(abandoned).hasSize(1);
    }

    private String startGoogleLogin() {
        return startLogin("google");
    }

    private String startLogin(String provider) {
        URI uri = service.authorize(provider);
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("state");
    }

    private void account(long id, String email, boolean active) {
        accounts.save(new Account(id, id, "EMP-" + id, email, "Ada", "Lovelace", "Employee", active, 1, null));
    }

    private static void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(OidcLoginException.class)
                .extracting(e -> ((OidcLoginException) e).code()).isEqualTo(code);
    }

    private static final class FakeProvider implements OidcProviderClient {
        private final OidcProvider provider;
        boolean enabled;
        ExternalIdentity identity;
        OidcProviderException failure;
        String lastChallenge;
        String lastRedirectUri;
        String lastVerifier;
        int exchanges;

        FakeProvider(OidcProvider provider, boolean enabled) {
            this.provider = provider;
            this.enabled = enabled;
        }

        @Override
        public OidcProvider provider() {
            return provider;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public URI authorizationUri(String state, String codeChallenge, String redirectUri) {
            lastChallenge = codeChallenge;
            lastRedirectUri = redirectUri;
            return URI.create("https://provider.test/auth?state=" + state);
        }

        @Override
        public ExternalIdentity exchange(String code, String codeVerifier, String redirectUri) {
            exchanges++;
            lastVerifier = codeVerifier;
            if (failure != null) {
                throw failure;
            }
            return identity;
        }
    }

    private static final class InMemoryAttempts implements LoginAttemptRepository {
        final Map<String, LoginAttempt> byState = new HashMap<>();

        @Override
        public void save(LoginAttempt attempt) {
            byState.put(attempt.state(), attempt);
        }

        @Override
        public Optional<LoginAttempt> take(String state) {
            return Optional.ofNullable(byState.remove(state));
        }

        @Override
        public void deleteExpired(Instant now) {
            byState.values().removeIf(attempt -> attempt.isExpiredAt(now));
        }
    }

    private static final class InMemoryLinks implements ExternalIdentityLinkRepository {
        private final Map<String, Long> links = new HashMap<>();

        @Override
        public Optional<Long> findEmployeeId(OidcProvider provider, String subject) {
            return Optional.ofNullable(links.get(provider + "/" + subject));
        }

        @Override
        public void link(OidcProvider provider, String subject, long employeeId) {
            links.putIfAbsent(provider + "/" + subject, employeeId);
        }
    }

    private static final class InMemoryAccounts implements AccountRepository {
        final Map<Long, Account> byEmployeeId = new HashMap<>();

        @Override
        public Optional<Account> findByEmployeeId(long employeeId) {
            return Optional.ofNullable(byEmployeeId.get(employeeId));
        }

        @Override
        public Optional<Account> findByEmail(String email) {
            return byEmployeeId.values().stream().filter(a -> a.email().equalsIgnoreCase(email)).findFirst();
        }

        @Override
        public Account save(Account account) {
            byEmployeeId.put(account.employeeId(), account);
            return account;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
