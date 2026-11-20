package dk.ek.shift_happens.identity.domain;

/**
 * Who the provider says the user is. {@code subject} is the provider's stable user id (Google's
 * {@code sub}, GitHub's numeric user id); {@code email} is only trusted when {@code emailVerified}.
 */
public record ExternalIdentity(OidcProvider provider, String subject, String email, boolean emailVerified) {
}
