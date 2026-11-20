package dk.ek.shift_happens.notification.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every HTTP request a correlation id (P1.6).
 *
 * <p>Takes {@code X-Correlation-Id} from the request when it is a safe token, otherwise generates a
 * UUID. The id is put in the MDC under {@code correlationId} (the log pattern prints it), echoed on the
 * response, and exposed through the request header so controllers that read the header see the same
 * id. The MDC is cleared when the request is done, because servlet threads are pooled.
 *
 * <p>Runs before Spring Security so rejected requests are logged with the id too. Copied per service
 * on purpose: there is no shared library (CLAUDE.md).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Free text in a header would end up in every log line: allow a plain token only. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = correlationIdOf(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(new CorrelatedRequest(request, correlationId), response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String correlationIdOf(String header) {
        return header != null && SAFE_ID.matcher(header).matches()
                ? header
                : UUID.randomUUID().toString();
    }

    /** Answers {@code X-Correlation-Id} with the effective id, whether it was sent or generated. */
    private static final class CorrelatedRequest extends HttpServletRequestWrapper {

        private final String correlationId;

        CorrelatedRequest(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? correlationId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(correlationId))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
