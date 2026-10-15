-- CustomerAgent v2: cosine ANN index for published intent examples.
-- Execute in the PostgreSQL database that owns rag_customer_service_intent_release_embedding.
CREATE INDEX IF NOT EXISTS idx_cs_release_emb_hnsw
    ON rag_customer_service_intent_release_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
