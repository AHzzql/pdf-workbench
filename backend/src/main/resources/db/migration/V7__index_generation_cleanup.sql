CREATE TABLE index_gc_candidates (
    workspace_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation UUID NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (workspace_id, document_id, generation)
);
CREATE INDEX index_gc_due ON index_gc_candidates(observed_at, workspace_id, document_id, generation);
