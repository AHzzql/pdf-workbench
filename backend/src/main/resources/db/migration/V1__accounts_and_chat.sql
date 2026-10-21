CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(96) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    credential_version BIGINT NOT NULL CHECK (credential_version > 0)
);
CREATE TABLE ai_documents (
    workspace_id UUID NOT NULL,
    id UUID NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (workspace_id, id)
);
CREATE TABLE ai_conversations (
    workspace_id UUID NOT NULL,
    id UUID NOT NULL,
    doc_id UUID NOT NULL,
    updated BIGINT NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (workspace_id, id),
    FOREIGN KEY (workspace_id, doc_id) REFERENCES ai_documents(workspace_id, id)
);
CREATE INDEX ai_conversations_recent ON ai_conversations(workspace_id, updated DESC, id);
CREATE INDEX ai_conversations_document ON ai_conversations(workspace_id, doc_id);
CREATE TABLE ai_messages (
    workspace_id UUID NOT NULL,
    id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    created BIGINT NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (workspace_id, id),
    FOREIGN KEY (workspace_id, conversation_id)
        REFERENCES ai_conversations(workspace_id, id) ON DELETE CASCADE
);
CREATE INDEX ai_messages_conversation ON ai_messages(workspace_id, conversation_id, created, id);
