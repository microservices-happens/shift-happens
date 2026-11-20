package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class EmployeeTermsRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(EmployeeTermsRefreshJob.class);

    private final EmployeeTermsRepository terms;
    private final EmployeeService employees;

    EmployeeTermsRefreshJob(EmployeeTermsRepository terms, EmployeeService employees) {
        this.terms = terms;
        this.employees = employees;
    }

    @Scheduled(fixedDelayString = "${app.employee-terms.refresh-delay:60000}")
    void refreshDueTerms() {
        refreshDueTerms(LocalDate.now(ZoneOffset.UTC));
    }

    public void refreshDueTerms(LocalDate today) {
        for (long employeeId : terms.dueEmployeeIds(today)) {
            try {
                employees.refreshTerms(employeeId, today);
            } catch (RuntimeException exception) {
                LOG.error("Could not refresh terms for employee {}", employeeId, exception);
            }
        }
    }
}
