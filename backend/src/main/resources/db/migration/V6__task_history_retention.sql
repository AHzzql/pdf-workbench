CREATE TABLE task_dead_letters (
    namespace VARCHAR(192) NOT NULL,
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    body_bytes INTEGER NOT NULL CHECK (body_bytes >= 0),
    task_id UUID,
    dispatch_version BIGINT CHECK (dispatch_version > 0),
    first_seen TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(namespace,fingerprint),
    CHECK ((task_id IS NULL) = (dispatch_version IS NULL))
);
CREATE INDEX task_dead_letters_expiry ON task_dead_letters(namespace,last_seen);
CREATE INDEX task_outbox_published_expiry ON task_outbox(published_at,id) WHERE published_at IS NOT NULL;
CREATE INDEX work_tasks_terminal_expiry ON work_tasks(updated_at,id) WHERE state IN ('SUCCEEDED','FAILED','CANCELLED');
