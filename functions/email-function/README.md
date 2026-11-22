# email-function (serverless)

An isolated background task, **not a microservice**: no API, no database. It sends one e-mail per
`notification.email.requested.v1` message on queue `email.send`.
**Compose:** `email-function`. **Kubernetes:** KEDA `ScaledJob` (`kubernetes/autoscaling/keda.yaml`).

> **Status: implemented (P4.4).** Real SMTP sender, three attempts then dead-letter, in-memory dedupe by
> `eventId`, optional idle-exit so the Kubernetes Job completes. `EmailFunctionIT` runs it against RabbitMQ
> and a real Mailpit container.

| | Local (docker-compose) | Kubernetes |
|---|---|---|
| Runs as | Long-running worker container (`EMAIL_FUNCTION_EXIT_WHEN_IDLE=false`) | **KEDA `ScaledJob`** with `EMAIL_FUNCTION_EXIT_WHEN_IDLE=true` and a `rabbitmq` trigger on `email.send` |
| Scale | 1 | 0 when the queue is empty; up to 5 Jobs while messages pile up |
| SMTP | Mailpit (http://localhost:8025) | Managed provider (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`) |

## Behaviour

1. Reads the envelope, checks `eventType == notification.email.requested.v1`, and validates `data.to`
   (`InternetAddress`, `strict`), `subject` and `body` against `contracts/events/payloads/email-request.schema.json`.
2. Sends through Spring's `JavaMailSender`; the message is acked only after SMTP accepted it, so a crash
   leads to a redelivery rather than a lost e-mail.
3. An unusable payload or an SMTP failure is retried three times (1 s, 2 s) and then republished to
   `shift-happens.dlx` with routing key `email.send`, landing in `email.send.dlq`. No infinite requeue.
4. With `EMAIL_FUNCTION_EXIT_WHEN_IDLE=true`, the process exits after it has sent at least one e-mail and
   the queue is empty, or after three consecutive empty polls if another Job already drained the burst.
   It calls `Runtime.halt(0)` because a graceful Spring close can block on listener shutdown; that is what
   lets the Kubernetes Job finish and KEDA start a fresh one later.

## Deduplication and its limit

The function is stateless, so it remembers the last 10 000 `eventId`s in memory (LRU). A redelivery in the
same process sends nothing twice; a redelivery **after a restart** can send a second e-mail. SMTP delivery is
not transactional by nature. The report lists this as a trade-off; a processed-events table or an
idempotency key at the provider would close it.

## Run and test

```bash
mvn -B -f functions/email-function/pom.xml verify
```

`EmailFunctionIT` proves: a request reaches Mailpit with the right `To`, `Subject` and `From`; the same
`eventId` arrives once; a payload without a recipient ends up in `email.send.dlq` instead of being requeued
forever.
