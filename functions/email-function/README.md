# email-function (serverless)

An isolated background task, **not a microservice**: it has no API and no database. It sends one email per `notification.email.requested.v1` message.

| | Local (docker-compose) | Kubernetes |
|---|---|---|
| Runs as | Long-running worker container | **KEDA `ScaledJob`** with a `rabbitmq` trigger on queue `email.send` |
| Scale | 1 | 0 when the queue is empty; up to N Jobs when messages pile up |
| SMTP | Mailpit (http://localhost:8025) | Managed email provider |

Each Job pulls messages until the queue is empty and then exits. It deduplicates by `eventId`, and it acks only after SMTP accepts the message, so a crash leads to a redelivery rather than a lost email.

Sketch of the Kubernetes resource (lives in `k8s/` later):

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledJob
metadata: { name: email-function }
spec:
  jobTargetRef:
    template:
      spec:
        containers: [{ name: email-function, image: ghcr.io/microservices-happens/email-function:1.0.0 }]
        restartPolicy: Never
  maxReplicaCount: 5
  triggers:
    - type: rabbitmq
      metadata: { queueName: email.send, mode: QueueLength, value: "20" }
      authenticationRef: { name: rabbitmq-auth }
```
