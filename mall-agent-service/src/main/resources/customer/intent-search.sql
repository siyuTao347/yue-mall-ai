WITH expected AS (
    SELECT * FROM jsonb_to_recordset(CAST(:expected_examples_json AS jsonb)) AS x(
        example_no text, example_version int, intent_code text,
        intent_version int, embedding_input_hash text
    )
), scored AS (
    SELECT v.example_no, v.example_version, v.intent_code, v.intent_version,
           1 - (v.embedding <=> CAST(:query_vector AS vector(1536))) AS score
      FROM rag_customer_service_intent_release_embedding v
      JOIN expected e ON v.example_no=e.example_no AND v.example_version=e.example_version
       AND v.intent_code=e.intent_code AND v.intent_version=e.intent_version
       AND v.embedding_input_hash=e.embedding_input_hash
     WHERE v.release_no=:release_no AND v.embedding_model=:embedding_model AND v.embedding_dim=1536
), integrity AS (
    SELECT
      (SELECT COUNT(*) FROM expected) AS manifest_count,
      (SELECT COUNT(DISTINCT example_no) FROM expected) AS unique_example_count,
      (SELECT COUNT(*) FROM rag_customer_service_intent_release_embedding
        WHERE release_no=:release_no) AS actual_count,
      COUNT(*) AS matched_count,
      COUNT(DISTINCT intent_code) AS matched_intent_count
    FROM scored
), best_per_intent AS (
    SELECT DISTINCT ON (intent_code) intent_code, intent_version, example_no, example_version, score
      FROM scored ORDER BY intent_code, score DESC, example_no
), candidates AS (
    SELECT b.* FROM best_per_intent b CROSS JOIN integrity i
     WHERE i.manifest_count=:expected_example_count
       AND i.unique_example_count=:expected_example_count
       AND i.actual_count=:expected_example_count
       AND i.matched_count=:expected_example_count
       AND i.matched_intent_count=:expected_intent_count
       AND i.matched_intent_count >= 2
     ORDER BY b.score DESC, b.intent_code LIMIT :intent_top_k
)
SELECT i.*, COALESCE(
    (SELECT jsonb_agg(to_jsonb(c) ORDER BY c.score DESC, c.intent_code) FROM candidates c),
    '[]'::jsonb) AS candidates
  FROM integrity i;
