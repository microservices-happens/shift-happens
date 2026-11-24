ALTER TABLE outbox_event
    ADD COLUMN traceparent VARCHAR(255),
    ADD COLUMN tracestate TEXT;
