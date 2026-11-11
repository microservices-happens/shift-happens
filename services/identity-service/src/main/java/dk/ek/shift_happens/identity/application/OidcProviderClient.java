package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.ExternalIdentity;
import dk.ek.shift_happens.identity.domain.OidcProvider;

import java.net.URI;

/** Port: talks to one provider (Google or GitHub). The HTTP and token checks live in infrastructure. */
public interface OidcProviderClient {

    OidcProvider provider();

    /** False when no client id is configured: the provider is then not offered at all. */
    boolean enabled();

    URI authorizationUri(String state, String codeChallenge, String redirectUri);

    /**
     * Exchanges the authorization code (with the PKCE verifier) and returns the verified identity.
     *
     * @throws OidcProviderException when the provider refuses or answers with something we cannot trust
     */
    ExternalIdentity exchange(String code, String codeVerifier, String redirectUri);
}
