package dk.ek.shift_happens.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {

    @Test
    void s256MatchesTheRfc7636Example() {
        // RFC 7636 Appendix B. If this drifts, Google and GitHub reject every code exchange.
        assertThat(Pkce.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
