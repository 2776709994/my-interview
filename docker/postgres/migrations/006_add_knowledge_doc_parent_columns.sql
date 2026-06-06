-- =========================================
-- 006: knowledge_documents 补父子文档列（schema 漂移修复）
-- =========================================
-- 背景：init.sql 历史版本未包含 parent_id / chunk_index，
-- 旧库靠运行期手工补列，新库（全新 volume 初始化）会缺列导致上传失败。
-- 本迁移保证任意存量库补齐这两列，与 init.sql 定义一致。

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name='knowledge_documents' AND column_name='parent_id') THEN
        ALTER TABLE knowledge_documents ADD COLUMN parent_id BIGINT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name='knowledge_documents' AND column_name='chunk_index') THEN
        ALTER TABLE knowledge_documents ADD COLUMN chunk_index INTEGER DEFAULT -1;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_parent_id ON knowledge_documents(parent_id);
