package dk.ek.shift_happens.emailfunction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Serverless job: drain queue email.send, send each email over SMTP, then exit.
 * KEDA starts a new Job while the queue has messages (see k8s/base/keda/email-function-scaledjob.yaml).
 */
@SpringBootApplication
public class EmailFunctionApplication {

    public static void main(String[] args) {
        // Exit when the work is done so the Kubernetes Job completes.
        System.exit(SpringApplication.exit(SpringApplication.run(EmailFunctionApplication.class, args)));
    }
}
