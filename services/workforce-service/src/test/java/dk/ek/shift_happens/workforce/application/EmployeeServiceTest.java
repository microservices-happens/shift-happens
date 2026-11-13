package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.domain.EmployeeRepository;
import dk.ek.shift_happens.workforce.domain.NewEmployee;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
