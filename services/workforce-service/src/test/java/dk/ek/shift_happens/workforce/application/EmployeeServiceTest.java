package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.domain.Employee;
import dk.ek.shift_happens.workforce.domain.EmployeeRepository;
import dk.ek.shift_happens.workforce.domain.NewEmployee;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EmployeeServiceTest {

    @Test
    void lateUpdateCannotWriteAnEmployeeOrEventAfterSoftDelete() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        EventOutbox outbox = mock(EventOutbox.class);
        EmployeeService service = new EmployeeService(employees, outbox,
                mock(EmployeeTermsRepository.class), "workforce-service");
        long deletedEmployeeId = 42L;
        when(employees.lockById(deletedEmployeeId)).thenReturn(Optional.empty());
        NewEmployee details = new NewEmployee("EMP-42", "Employee", "Ada", "Lovelace",
                "ada@shift.dk", null, null, "ACTIVE", null);

        assertThatThrownBy(() -> service.update(deletedEmployeeId, details, "late-update"))
                .isInstanceOf(EmployeeNotFoundException.class);

        verify(employees).lockById(deletedEmployeeId);
        verifyNoMoreInteractions(employees);
        verifyNoInteractions(outbox);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void theRequestCorrelationIdFromTheMdcIsCopiedIntoTheEvent() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        EventOutbox outbox = mock(EventOutbox.class);
        EmployeeService service = new EmployeeService(employees, outbox,
                mock(EmployeeTermsRepository.class), "workforce-service");
        when(employees.save(any())).thenAnswer(call -> {
            Employee unsaved = call.getArgument(0);
            return new Employee(7L, unsaved.employeeNumber(), unsaved.userRole(), unsaved.firstName(),
                    unsaved.lastName(), unsaved.email(), unsaved.phoneNumber(), unsaved.hireDate(),
                    unsaved.employmentStatus(), unsaved.primaryWorkLocationId(), unsaved.aggregateVersion());
        });
        NewEmployee details = new NewEmployee("EMP-7", "Employee", "Grace", "Hopper",
                "grace@shift.dk", null, null, "ACTIVE", null);
        MDC.put("correlationId", "from-filter");

        service.create(details, null);

        ArgumentCaptor<EventEnvelope> published = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(published.capture());
        assertThat(published.getValue().correlationId()).isEqualTo("from-filter");
    }

    @Test
    void correlationIdPrefersTheCallerThenTheMdcThenANewUuid() {
        MDC.put("correlationId", "from-mdc");
        assertThat(EmployeeService.correlationIdOf("from-caller")).isEqualTo("from-caller");
        assertThat(EmployeeService.correlationIdOf(" ")).isEqualTo("from-mdc");

        MDC.clear();
        assertThat(UUID.fromString(EmployeeService.correlationIdOf(null))).isNotNull();
    }
}
