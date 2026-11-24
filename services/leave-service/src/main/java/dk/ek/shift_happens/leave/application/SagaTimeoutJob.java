package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSagaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Compensates leave-approval sagas whose deadline passed without an answer from Scheduling. The candidates are read
 * without a lock; each one is then handled in its own transaction by {@link LeaveApprovalSagaService#timeOut}, which
 * locks the request row (skipping a locked one) and re-checks the saga, so a reply that arrives at the same moment
 * and the timeout cannot both decide.
 */
@Component
@Profile("command")
public class SagaTimeoutJob {

    static final int BATCH_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutJob.class);

    private final LeaveApprovalSagaRepository sagas;
    private final LeaveApprovalSagaService service;
    private final Clock clock;
    private final Counter timeouts;

    SagaTimeoutJob(LeaveApprovalSagaRepository sagas, LeaveApprovalSagaService service, Clock clock,
                   MeterRegistry metrics) {
        this.sagas = sagas;
        this.service = service;
        this.clock = clock;
        this.timeouts = metrics.counter("shift.leave.saga.timeouts");
    }

    @Scheduled(fixedDelayString = "${app.saga.poll-delay}")
    public void run() {
        runOnce();
    }

    /** Handles every overdue attempt, one batch at a time; returns how many were compensated. */
    public int runOnce() {
        int compensated = 0;
        List<LeaveApprovalSaga> overdue;
        int skipped;
        do {
            overdue = sagas.findOverdue(clock.instant(), BATCH_SIZE);
            skipped = 0;
            for (LeaveApprovalSaga saga : overdue) {
                try {
                    if (service.timeOut(saga.sagaId(), saga.leaveRequestId())) {
                        compensated++;
                        timeouts.increment();
                        log.info("Compensated attempt {} of leave request {}: no answer before {}", saga.attempt(),
                                saga.leaveRequestId(), saga.deadline());
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    skipped++;
                    log.error("Could not time out attempt {} of leave request {}", saga.attempt(),
                            saga.leaveRequestId(), e);
                }
            }
        } while (!overdue.isEmpty() && skipped == 0); // a skipped one would come back forever: next poll
        return compensated;
    }
}
