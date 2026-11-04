CREATE TABLE processed_event (event_id UUID PRIMARY KEY);
CREATE TABLE suggestion_log (
    event_id UUID PRIMARY KEY REFERENCES processed_event(event_id),
    shift_id BIGINT NOT NULL,
    prompt TEXT NOT NULL,
    model_response TEXT,
    source VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
