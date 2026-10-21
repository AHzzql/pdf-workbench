CREATE TABLE work_tasks (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('GENERATE','INDEX','CLEAN')),
    target_id UUID NOT NULL,
    document_id UUID NOT NULL,
    conversation_id UUID,
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','RETRY','SUCCEEDED','FAILED','CANCELLED')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence >= 0),
    dispatch_version BIGINT NOT NULL DEFAULT 1 CHECK (dispatch_version > 0),
    lease_until TIMESTAMPTZ,
    deadline TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    stage VARCHAR(96) NOT NULL DEFAULT '等待处理',
    error_code VARCHAR(64) NOT NULL DEFAULT '',
    CHECK ((state = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((kind = 'GENERATE') = (conversation_id IS NOT NULL))
);
CREATE UNIQUE INDEX work_tasks_active_target ON work_tasks(workspace_id,kind,target_id)
    WHERE state IN ('QUEUED','RUNNING','RETRY');
CREATE UNIQUE INDEX work_tasks_generation_idempotency ON work_tasks(workspace_id,target_id) WHERE kind='GENERATE';
CREATE UNIQUE INDEX work_tasks_active_conversation ON work_tasks(workspace_id,conversation_id)
    WHERE kind='GENERATE' AND state IN ('QUEUED','RUNNING','RETRY');
CREATE INDEX work_tasks_recovery ON work_tasks(state,lease_until,available_at);
CREATE INDEX work_tasks_workspace ON work_tasks(workspace_id,state,kind);
CREATE INDEX work_tasks_document ON work_tasks(workspace_id,document_id,state);
CREATE TABLE task_outbox (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES work_tasks(id) ON DELETE CASCADE,
    dispatch_version BIGINT NOT NULL,
    route VARCHAR(16) NOT NULL CHECK (route IN ('reading','index','cleanup','dead')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    UNIQUE(task_id,dispatch_version,route),
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX task_outbox_delivery ON task_outbox(available_at,id) WHERE published_at IS NULL;
