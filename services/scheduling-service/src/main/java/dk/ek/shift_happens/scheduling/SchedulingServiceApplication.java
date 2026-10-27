package dk.ek.shift_happens.scheduling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One codebase, two deployments: APP_ROLE=command runs the Postgres write model and the outbox
 * relay, APP_ROLE=query will run the Mongo read model and GraphQL (P2.10/P2.11).
 */
@SpringBootApplication
@EnableScheduling
public class SchedulingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulingServiceApplication.class, args);
    }
}
