package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security baseline of #88: the real filter chain of the command role, real RS256 tokens, real
 * database. A missing token is 401; a role that may not write is 403; an Employee may submit only their
 * own leave request (contracts/README.md), anything else is 403.
 */
class LeaveSecurityIT extends AbstractCommandIT {

    @Test
    void withoutATokenEveryEndpointRefuses() {
        var anonymous = http.exchange("/v1/leavetypes", HttpMethod.GET,
                new HttpEntity<>(TestJwks.anonymous()), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void anEmployeeMaySubmitTheirOwnRequestButNotSomeoneElses() {
        seedEmployee(4711);
        long typeId = createLeaveType("Vacation-" + UUID.randomUUID());
        String ownRequest = """
                {"employeeId":4711,"leaveTypeId":%d,"startDate":"2026-12-01","endDate":"2026-12-02"}
                """.formatted(typeId);

        var own = postAs("/v1/leaverequests", ownRequest, TestJwks.employee(4711));
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String someoneElse = """
                {"employeeId":4712,"leaveTypeId":%d,"startDate":"2026-12-03","endDate":"2026-12-04"}
                """.formatted(typeId);
        var foreign = postAs("/v1/leaverequests", someoneElse, TestJwks.employee(4711));
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreign.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void anEmployeeMayNotApproveLeave() {
        var response = postAs("/v1/leaveapprovals", """
                {"leaveRequestId":1,"approverEmployeeId":4711,"decision":"APPROVED"}
                """, TestJwks.employee(4711));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aManagerMayWriteAndAnExpiredTokenIsRefused() {
        long typeId = createLeaveType("Manager-" + UUID.randomUUID());
        assertThat(typeId).isPositive();

        var expired = http.exchange("/v1/leavetypes", HttpMethod.GET,
                new HttpEntity<>(TestJwks.bearer(TestJwks.expiredToken(1, "Manager"))), JsonNode.class);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private org.springframework.http.ResponseEntity<String> postAs(String path, String body, String token) {
        HttpHeaders headers = TestJwks.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
