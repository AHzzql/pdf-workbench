CREATE TABLE document_files (
    workspace_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation UUID NOT NULL,
    bucket VARCHAR(63) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('STAGING','READY','GARBAGE')),
    pdf_bytes BIGINT NOT NULL CHECK (pdf_bytes BETWEEN 1 AND 52428800),
    pdf_sha256 VARCHAR(64) NOT NULL CHECK (pdf_sha256 ~ '^[a-f0-9]{64}$'),
    content_bytes BIGINT NOT NULL CHECK (content_bytes BETWEEN 1 AND 33554432),
    content_sha256 VARCHAR(64) NOT NULL CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
    lease_until TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    cleanup_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    cleanup_token UUID,
    cleanup_lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (workspace_id,document_id,generation),
    CHECK ((state='STAGING' AND lease_until IS NOT NULL)
        OR (state<>'STAGING' AND lease_until IS NULL)),
    CHECK (state<>'READY' OR verified_at IS NOT NULL),
    CHECK ((cleanup_token IS NULL) = (cleanup_lease_until IS NULL))
);
CREATE UNIQUE INDEX document_files_ready ON document_files(workspace_id,document_id) WHERE state='READY';
CREATE INDEX document_files_staging ON document_files(bucket,lease_until) WHERE state='STAGING';
CREATE INDEX document_files_cleanup ON document_files(bucket,cleanup_at) WHERE state='GARBAGE';
