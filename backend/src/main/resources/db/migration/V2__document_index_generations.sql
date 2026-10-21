ALTER TABLE ai_documents ADD COLUMN index_generation UUID;
ALTER TABLE ai_documents ADD COLUMN index_build_token UUID;
ALTER TABLE ai_documents ADD COLUMN index_lease_until TIMESTAMPTZ;
ALTER TABLE ai_documents ADD CONSTRAINT ai_document_index_lease
    CHECK ((index_build_token IS NULL) = (index_lease_until IS NULL));
