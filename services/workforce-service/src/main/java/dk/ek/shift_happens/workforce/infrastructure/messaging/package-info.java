/**
 * RabbitMQ adapters: queue/binding declarations, event listeners (idempotent by eventId,
 * highest aggregateVersion wins) and the outbox relay. Payloads follow contracts/asyncapi.yaml.
 */
package dk.ek.shift_happens.workforce.infrastructure.messaging;
