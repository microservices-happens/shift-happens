package dk.ek.shift_happens.audit.api;

import dk.ek.shift_happens.audit.application.AuditLogService;
import dk.ek.shift_happens.audit.domain.AuditEntry;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /v1/auditlogs (contracts/openapi/audit.yaml), Administrator only (SecurityConfig). The audit log is
 * read-only: there is no endpoint that changes or deletes an entry.
 */
@RestController
@RequestMapping("/v1/auditlogs")
class AuditController {

    private final AuditLogService service;

    AuditController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    List<AuditEntry> search(@RequestParam(required = false) String eventType,
                            @RequestParam(required = false) Long aggregateId,
                            @RequestParam(required = false) String correlationId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                            OffsetDateTime from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                            OffsetDateTime to) {
        return service.search(eventType, aggregateId, correlationId, from, to);
    }
}
