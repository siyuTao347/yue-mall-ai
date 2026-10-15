WITH scored AS (
    SELECT v.intent_code, v.intent_version, v.example_no, v.example_version,
           1 - (v.embedding <=> CAST(:query_vector AS vector(1536))) AS score
      FROM rag_customer_service_intent_release_embedding v
     WHERE v.release_no = :release_no
       AND v.embedding_model = :embedding_model
       AND v.embedding_dim = 1536
), best_per_intent AS (
    SELECT DISTINCT ON (intent_code)
           intent_code, intent_version, example_no, example_version, score
      FROM scored
     ORDER BY intent_code, score DESC, example_no
)
SELECT COALESCE(
    jsonb_agg(to_jsonb(b) ORDER BY b.score DESC, b.intent_code),
    '[]'::jsonb
) AS candidates
  FROM (
      SELECT * FROM best_per_intent
       ORDER BY score DESC, intent_code
       LIMIT :intent_top_k
  ) b;
