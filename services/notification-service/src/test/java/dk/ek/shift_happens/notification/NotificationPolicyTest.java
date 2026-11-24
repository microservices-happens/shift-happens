package dk.ek.shift_happens.notification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business rules of the notification service. These assertions are the specification of the
 * messages the frontend and Mailpit show, so they fail if a rule changes silently.
 */
class NotificationPolicyTest {

    @Test
    void anAssignmentMessageNamesTheShiftAndItsWindow() {
        NotificationDraft draft = NotificationPolicy.assignmentCreated(7, "Evening shift",
                "2026-11-21 16:00 UTC", "2026-11-21 22:00 UTC");

        assertThat(draft.employeeId()).isEqualTo(7);
        assertThat(draft.title()).isEqualTo("New shift assignment");
        assertThat(draft.message()).contains("Evening shift", "2026-11-21 16:00 UTC");
    }

    @Test
    void onlyStatusChangesWorthKnowingBecomeAnUpdateNotification() {
        assertThat(NotificationPolicy.assignmentUpdateIsNotified("RELEASED_FOR_LEAVE")).isTrue();
        assertThat(NotificationPolicy.assignmentUpdateIsNotified("CANCELLED")).isTrue();
        assertThat(NotificationPolicy.assignmentUpdateIsNotified("SWAPPED")).isTrue();
        // Check-in/out and a first assignment are noise; the employee already has the assignment message.
        assertThat(NotificationPolicy.assignmentUpdateIsNotified("ASSIGNED")).isFalse();
        assertThat(NotificationPolicy.assignmentUpdateIsNotified("CHECKED_IN")).isFalse();
    }

    @Test
    void aSwapRequestGoesToTheColleagueWhoIsAsked() {
        NotificationDraft draft = NotificationPolicy.swapRequested(57, 17, 42);

        assertThat(draft.employeeId()).isEqualTo(57);
        assertThat(draft.message()).contains("Employee 42", "swap 17");
    }

    @Test
    void aRejectionCarriesTheReasonWhenThereIsOne() {
        assertThat(NotificationPolicy.swapRejected(42, 17, "Too short notice").message())
                .endsWith("was rejected: Too short notice.");
        assertThat(NotificationPolicy.swapRejected(42, 17, " ").message())
                .endsWith("was rejected.");
    }

    @Test
    void everyRelevantEventIsAlsoEmailedExceptLowValueShiftUpdates() {
        assertThat(NotificationPolicy.emailRequested("scheduling.assignment.created.v1")).isTrue();
        assertThat(NotificationPolicy.emailRequested("leave.request.approved.v1")).isTrue();
        assertThat(NotificationPolicy.emailRequested("scheduling.shift.updated.v1")).isFalse();
    }

    @Test
    void onlyAnActiveEmployeeWithAnAddressIsEmailed() {
        assertThat(new EmployeeContact(1, "a@shift.dk", "Employee", "ACTIVE", false).emailable()).isTrue();
        assertThat(new EmployeeContact(2, "a@shift.dk", "Employee", "INACTIVE", false).emailable()).isFalse();
        assertThat(new EmployeeContact(3, "a@shift.dk", "Employee", "ACTIVE", true).emailable()).isFalse();
        assertThat(new EmployeeContact(4, "", "Employee", "ACTIVE", false).emailable()).isFalse();
    }

    @Test
    void onlyAnActiveEmployeeWithTheManagerRoleIsAManager() {
        assertThat(new EmployeeContact(1, "m@shift.dk", "Manager", "ACTIVE", false).manager()).isTrue();
        assertThat(new EmployeeContact(2, "m@shift.dk", "Employee", "ACTIVE", false).manager()).isFalse();
        assertThat(new EmployeeContact(3, "m@shift.dk", "Manager", "INACTIVE", false).manager()).isFalse();
        assertThat(new EmployeeContact(4, "m@shift.dk", "Manager", "ACTIVE", true).manager()).isFalse();
    }
}
