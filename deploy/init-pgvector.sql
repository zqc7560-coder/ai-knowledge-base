-- pgvector 初始化：扩展 + 向量表（与 MySQL document_chunks 一对一）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_vectors (
  id BIGSERIAL PRIMARY KEY,
  chunk_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  doc_id BIGINT NOT NULL,
  content TEXT NOT NULL,
  embedding vector(1024) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_vec_chunk ON kb_vectors (chunk_id);
CREATE INDEX IF NOT EXISTS idx_vec_kb ON kb_vectors (kb_id);
-- HNSW：m/ef 取默认偏保守值，M2 压测后再调
CREATE INDEX IF NOT EXISTS idx_vec_hnsw ON kb_vectors USING hnsw (embedding vector_ip_ops) WITH (m = 16, ef_construction = 64);
