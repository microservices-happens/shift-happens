package dk.ek.shift_happens.identity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcInChain = new AtomicReference<>();
    private final AtomicReference<HttpServletRequest> requestInChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> {
        mdcInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        requestInChain.set((HttpServletRequest) req);
    };

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsTheIncomingIdInMdcResponseAndRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");

        filter.doFilter(request, response, chain);

        assertThat(mdcInChain.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(requestInChain.get().getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
    }

    @Test
    void generatesAUuidWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");

        filter.doFilter(request, response, chain);

        String generated = mdcInChain.get();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(generated);
        assertThat(requestInChain.get().getHeader("x-correlation-id")).isEqualTo(generated);
        assertThat(Collections.list(requestInChain.get().getHeaders(CorrelationIdFilter.HEADER)))
                .containsExactly(generated);
        assertThat(Collections.list(requestInChain.get().getHeaderNames()))
                .contains(CorrelationIdFilter.HEADER);
    }

    @Test
    void replacesAnUnsafeHeaderInsteadOfLoggingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(CorrelationIdFilter.HEADER, "evil\nINFO forged log line");

        filter.doFilter(request, response, chain);

        assertThat(mdcInChain.get()).doesNotContain("evil");
        assertThat(UUID.fromString(mdcInChain.get())).isNotNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(mdcInChain.get());
    }

    @Test
    void rejectsBlankAndOverlongIds() {
        assertThat(CorrelationIdFilter.correlationIdOf("")).isNotEmpty().isNotEqualTo("");
        assertThat(CorrelationIdFilter.correlationIdOf("x".repeat(129))).hasSize(36);
        assertThat(CorrelationIdFilter.correlationIdOf("x".repeat(128))).isEqualTo("x".repeat(128));
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/health"), response, chain);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsTheMdcWhenTheChainThrows() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/health"), response, failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
