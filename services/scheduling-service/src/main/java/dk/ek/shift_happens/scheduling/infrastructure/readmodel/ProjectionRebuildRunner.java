package dk.ek.shift_happens.scheduling.infrastructure.readmodel;

import dk.ek.shift_happens.scheduling.application.SchedulingProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Operational entry point for the rebuild (P2.10): start the query role with
 * {@code PROJECTION_REBUILD_ON_START=true} and it replays the logged events into empty collections
 * before serving reads.
 *
 * <p>Off by default, because a rebuild is a deliberate operation, not something a restart does.
 */
@Component
@Profile("query")
class ProjectionRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildRunner.class);

    private final SchedulingProjectionService projection;
    private final boolean rebuildOnStart;

    ProjectionRebuildRunner(SchedulingProjectionService projection,
                            @Value("${app.projection.rebuild-on-start:false}") boolean rebuildOnStart) {
        this.projection = projection;
        this.rebuildOnStart = rebuildOnStart;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!rebuildOnStart) {
            return;
        }
        long replayed = projection.rebuild();
        log.info("Read-model rebuild finished: {} events replayed", replayed);
    }
}
