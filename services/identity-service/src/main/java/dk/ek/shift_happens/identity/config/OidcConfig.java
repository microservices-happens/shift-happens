package dk.ek.shift_happens.identity.config;

import dk.ek.shift_happens.identity.application.OidcLoginService;
import dk.ek.shift_happens.identity.application.OidcProviderClient;
import dk.ek.shift_happens.identity.application.TokenIssuer;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.ExternalIdentityLinkRepository;
import dk.ek.shift_happens.identity.domain.LoginAttemptRepository;
import dk.ek.shift_happens.identity.infrastructure.oidc.GitHubOidcClient;
import dk.ek.shift_happens.identity.infrastructure.oidc.GoogleOidcClient;
import dk.ek.shift_happens.identity.infrastructure.oidc.OidcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Google/GitHub login (P2.3). A hand-written authorization-code flow instead of Spring's
 * {@code oauth2Login}, which needs an HTTP session and its own URL shapes; the contract fixes
 * {@code /v1/auth/oidc/{provider}/authorize|callback} and the service is stateless.
 */
@Configuration
@EnableConfigurationProperties(OidcProperties.class)
class OidcConfig {

    /** Calls to Google/GitHub must not hang a request thread when the provider is slow. */
    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    private static RestClient providerHttp() {
        return RestClient.builder()
                .requestFactory(requestFactory())
                // GitHub's API rejects requests without a User-Agent.
                .defaultHeader(HttpHeaders.USER_AGENT, "shift-happens-identity")
                .build();
    }

    @Bean
    GoogleOidcClient googleOidcClient(OidcProperties properties) {
        return new GoogleOidcClient(properties.google(), providerHttp(), new RestTemplate(requestFactory()));
    }

    @Bean
    GitHubOidcClient gitHubOidcClient(OidcProperties properties) {
        return new GitHubOidcClient(properties.github(), providerHttp());
    }

    @Bean
    OidcLoginService oidcLoginService(OidcProperties properties, List<OidcProviderClient> clients,
                                      LoginAttemptRepository attempts, ExternalIdentityLinkRepository links,
                                      AccountRepository accounts, TokenIssuer tokenIssuer) {
        return new OidcLoginService(clients, attempts, links, accounts, tokenIssuer,
                properties.redirectBaseUrl(), properties.stateTtl(), Clock.systemUTC());
    }
}
