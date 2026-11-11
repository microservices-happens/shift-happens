ALTER TABLE notification
DROP CONSTRAINT uq_notification_source_event_id;

ALTER TABLE notification
ADD CONSTRAINT uq_notification_source_event_employee
UNIQUE (source_event_id, employee_id);