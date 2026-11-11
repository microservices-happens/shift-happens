
ALTER TABLE notification
ADD CONSTRAINT uq_notification_source_event_id
UNIQUE (source_event_id);
