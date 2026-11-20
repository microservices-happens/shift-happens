package dk.ek.shift_happens.emailfunction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Serverless job: drain queue email.send, send each email over SMTP, then exit when the queue is empty.
 * KEDA starts a new Job while the queue has messages (k8s/base/keda/email-function-scaledjob.yaml).
 *
 * <p>Locally (Compose) the process stays up: {@code EMAIL_FUNCTION_EXIT_WHEN_IDLE} is false there. In
 * Kubernetes the ScaledJob sets it to true, so the Job completes instead of idling.
 */
@SpringBootApplication
@EnableScheduling
public class EmailFunctionApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmailFunctionApplication.class, args);
    }
}
