#!/usr/bin/env node
/**
 * Publishes a burst of valid notification.email.requested.v1 events so KEDA can scale the email
 * ScaledJob. Point it at the Kubernetes RabbitMQ management endpoint (port-forward or NodePort):
 *
 *   kubectl -n shift-happens port-forward svc/rabbitmq 15672:15672
 *   node scripts/keda-email-burst.mjs 500
 *
 * Defaults: http://localhost:15672, vhost shift-happens, user shift / shift_dev.
 * This is a demo/load helper, not part of a service.
 */
const API = (process.env.RABBIT_API ?? 'http://localhost:15672').replace(/\/$/, '');
const USER = process.env.RABBIT_USER ?? 'shift';
const PASSWORD = process.env.RABBIT_PASSWORD ?? 'shift_dev';
const COUNT = Number(process.argv[2] ?? process.env.BURST ?? 100);
const VHOST = process.env.RABBIT_VHOST ?? 'shift-happens';
const EXCHANGE = process.env.RABBIT_EXCHANGE ?? 'shift-happens.events';
const ROUTING_KEY = 'notification.email.requested.v1';
const CONCURRENCY = Number(process.env.BURST_CONCURRENCY ?? 20);

const auth = `Basic ${Buffer.from(`${USER}:${PASSWORD}`).toString('base64')}`;

async function publish(index) {
  const envelope = {
    eventId: crypto.randomUUID(),
    eventType: ROUTING_KEY,
    occurredAt: new Date().toISOString(),
    producer: 'keda-demo',
    aggregateId: index,
    aggregateVersion: 1,
    correlationId: crypto.randomUUID(),
    causationId: null,
    data: {
      to: `burst-${index}@example.com`,
      subject: `KEDA burst ${index}`,
      body: 'Hello from the KEDA burst generator',
    },
  };
  const response = await fetch(
    `${API}/api/exchanges/${encodeURIComponent(VHOST)}/${encodeURIComponent(EXCHANGE)}/publish`,
    {
      method: 'POST',
      headers: { Authorization: auth, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        properties: { delivery_mode: 2, content_type: 'application/json' },
        routing_key: ROUTING_KEY,
        payload: JSON.stringify(envelope),
        payload_encoding: 'string',
      }),
    }
  );
  if (!response.ok) throw new Error(`publish failed: HTTP ${response.status} ${await response.text()}`);
}

async function main() {
  let next = 0;
  async function worker() {
    while (true) {
      const index = next++;
      if (index >= COUNT) return;
      await publish(index);
    }
  }
  const started = Date.now();
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  console.log(`published ${COUNT} email requests in ${((Date.now() - started) / 1000).toFixed(1)}s`);
}

main().catch((error) => {
  console.error(`burst: ${error.message}`);
  process.exit(1);
});
