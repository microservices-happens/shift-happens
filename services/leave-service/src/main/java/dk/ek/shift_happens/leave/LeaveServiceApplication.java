package dk.ek.shift_happens.leave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @EnableScheduling drives the outbox relay in the command role. */
@SpringBootApplication
@EnableScheduling
public class LeaveServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeaveServiceApplication.class, args);
    }
}
