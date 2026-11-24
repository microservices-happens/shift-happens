#!/usr/bin/env python3
"""
High-rate load generator for the notification-service autoscaling demo.

The RabbitMQ management HTTP API tops out around 100 publishes/second on this laptop,
which is below the consumer's drain rate. This script uses AMQP 0-9-1 directly, so a
10,000-message backlog appears in a few seconds and KEDA has something to scale on.

Prerequisites:
    python -m pip install pika
    kubectl -n shift-happens port-forward svc/rabbitmq 5672:5672

Usage:
    python scripts/notification-burst.py 10000

Environment:
    RABBITMQ_HOST (default 127.0.0.1), RABBITMQ_PORT (5672),
    RABBITMQ_USER (shift), RABBITMQ_PASSWORD, RABBITMQ_VHOST (shift-happens).

The recipient is employee 9999, which is intentionally absent from the workforce replica:
notification-service stores in-app rows but does not create email requests, so the graph
shows queue backlog and consumer replicas rather than Mailpit traffic.
"""
import json
import os
import sys
import time
import uuid
import warnings
from datetime import datetime, timedelta, timezone

warnings.filterwarnings("ignore", message=".*BlockingConnection is deprecated.*")

import pika

COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.getenv("BURST", "10000"))
HOST = os.getenv("RABBITMQ_HOST", "127.0.0.1")
PORT = int(os.getenv("RABBITMQ_PORT", "5672"))
USER = os.getenv("RABBITMQ_USER", "shift")
PASSWORD = os.getenv("RABBITMQ_PASSWORD", "shift_dev")
VHOST = os.getenv("RABBITMQ_VHOST", "shift-happens")
EXCHANGE = os.getenv("EVENT_EXCHANGE", "shift-happens.events")
ROUTING_KEY = "scheduling.assignment.created.v1"


def envelope(index: int) -> dict:
    now = datetime.now(timezone.utc)
    start = now + timedelta(days=1)
    end = start + timedelta(hours=8)
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": ROUTING_KEY,
        "occurredAt": now.isoformat(),
        "producer": "autoscaling-demo",
        "aggregateId": index,
        "aggregateVersion": index + 1,
        "correlationId": str(uuid.uuid4()),
        "causationId": None,
        "data": {
            "shiftAssignmentId": 1_000_000 + index,
            "shiftId": 1,
            "employeeId": 9999,
            "assignmentStatus": "ASSIGNED",
            "assignedDatetime": now.isoformat(),
            "shiftStartDatetime": start.isoformat(),
            "shiftEndDatetime": end.isoformat(),
        },
    }


def main() -> None:
    credentials = pika.PlainCredentials(USER, PASSWORD)
    parameters = pika.ConnectionParameters(
        host=HOST,
        port=PORT,
        virtual_host=VHOST,
        credentials=credentials,
        heartbeat=30,
        blocked_connection_timeout=30,
    )
    connection = pika.BlockingConnection(parameters)
    channel = connection.channel()
    # This is a load generator, not a producer in the transactional-outbox sense: waiting for
    # a publisher confirm on every message would cap the rate at roughly 140 msg/s on this
    # laptop and hide the scaling behaviour we want to demonstrate.
    properties = pika.BasicProperties(content_type="application/json", delivery_mode=2)
    started = time.monotonic()
    for index in range(COUNT):
        channel.basic_publish(
            exchange=EXCHANGE,
            routing_key=ROUTING_KEY,
            body=json.dumps(envelope(index)),
            properties=properties,
        )
        if index and index % 1000 == 0:
            elapsed = time.monotonic() - started
            print(f"{index} published ({index / elapsed:.0f} msg/s)", flush=True)
    elapsed = time.monotonic() - started
    connection.close()
    print(f"published {COUNT} notification events in {elapsed:.1f}s")


if __name__ == "__main__":
    try:
        main()
    except pika.exceptions.AMQPConnectionError as error:
        raise SystemExit(
            f"cannot reach RabbitMQ at {HOST}:{PORT}: {error}\n"
            "Start the port-forward: kubectl -n shift-happens port-forward svc/rabbitmq 5672:5672"
        )
    except pika.exceptions.UnroutableError as error:
        raise SystemExit(f"RabbitMQ could not route the event: {error}")
