-- ============================================================================
-- PGvector 向量表参考结构
--
-- 说明：应用正常运行时由 Spring AI 的 PgVectorStore 自动建表并依据
--       spring.ai.vectorstore.pgvector.dimensions 指定向量维度，因此本文件
--       通常不需要手动执行。保留它是为了便于 DBA 审阅索引策略，或在
--       需要手动介入（如维度调整、索引重建）时提供基线。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- Spring AI PgVectorStore 默认表结构（版本 1.1.x）
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid         DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text         NOT NULL,
    metadata  jsonb,
    embedding vector(384)  NOT NULL
);

-- HNSW 索引：在召回率与查询性能之间取得平衡，适合中小规模知识库
CREATE INDEX IF NOT EXISTS vector_store_cosine_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- 多知识库隔离：按 knowledge_base_id 做部分索引，缩小过滤扫描范围
CREATE INDEX IF NOT EXISTS vector_store_kb_idx
    ON vector_store ((metadata ->> 'knowledge_base_id'));

-- 引用回溯：按文档 ID 定位全部切片
CREATE INDEX IF NOT EXISTS vector_store_document_idx
    ON vector_store ((metadata ->> 'document_id'));
