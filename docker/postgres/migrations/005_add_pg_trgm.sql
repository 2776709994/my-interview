-- =========================================
-- 005: 关键词检索路（pg_trgm 三元组索引）
-- =========================================
-- 用途：RAG 双路检索中的"关键词路"。
--   - pg_trgm 提供 similarity() / word_similarity() 与 % / ILIKE 加速
--   - GIN 索引加速 content ILIKE '%keyword%' 子串匹配（中文友好，无需分词器）
-- 注意：pg_trgm 对 <3 字符的关键词无法走索引（自动回退顺序扫描，不影响正确性）

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_content_trgm
    ON knowledge_documents USING gin (content gin_trgm_ops);
