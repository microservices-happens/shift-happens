# email-function (serverless)

An isolated background task, **not a microservice**: it has no API and no database. It sends one email per `notification.email.requested.v1` message.

> **Status: skeleton (P4.4 not started).** `EmailFunctionApplication` starts Spring Boot without a web
> server (`web-application-type: none`) and exits at once. There is no RabbitMQ listener, no SMTP sender
> and no deduplication yet; the `RABBITMQ_*` and `SMTP_*` variables that `docker-compose.microservices.yml`
> and `kubernetes/email-function/deployment.yaml` set are not read. Nothing publishes
> `notification.email.requested.v1` yet either (notification-service, P4.3). The rest of this README is the
> design, **not implemented yet**.

| | Local (docker-compose) | Kubernetes |
|---|---|---|
| Runs as | Long-running worker container | **KEDA `ScaledJob`** with a `rabbitmq` trigger on queue `email.send` |
| Scale | 1 | 0 when the queue is empty; up to N Jobs when messages pile up |
| SMTP | Mailpit (http://localhost:8025) | Managed email provider |

Each Job pulls messages until the queue is empty and then exits. It deduplicates by `eventId`, and it acks only after SMTP accepts the message, so a crash leads to a redelivery rather than a lost email.

What runs today: `kubernetes/email-function/deployment.yaml` is a plain Deployment (it restarts after every
exit, see "Known issues" in `kubernetes/README.md`; Compose restarts it the same way). The KEDA `ScaledJob` exists only in the older,
unused Kustomize scaffold at `k8s/base/keda/email-function-scaledjob.yaml` (KEDA autoscaling is P7.3):

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledJob
metadata: { name: email-function }
spec:
  jobTargetRef:
    template:
      spec:
        containers: [{ name: email-function, image: ghcr.io/microservices-happens/email-function:0.1.0 }]
        restartPolicy: Never
  pollingInterval: 10
  maxReplicaCount: 5
  triggers:
    - type: rabbitmq
      metadata: { queueName: email.send, mode: QueueLength, value: "20" }
      authenticationRef: { name: rabbitmq-auth }
```

Test: `./mvnw -B -f functions/email-function/pom.xml test` runs only `EmailFunctionApplicationTests.contextLoads`
(a placeholder until the SMTP sender exists).
