-- 面试与知识库模块增量迁移脚本
-- 1. interview_questions 增加 topic_summary 列（历史面试去重）
-- 2. knowledge_documents 增加 file_hash 列（知识库 MD5 查重）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='interview_questions' AND column_name='topic_summary') THEN
        ALTER TABLE interview_questions ADD COLUMN topic_summary VARCHAR(255);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='knowledge_documents' AND column_name='file_hash') THEN
        ALTER TABLE knowledge_documents ADD COLUMN file_hash VARCHAR(64);
        CREATE INDEX IF NOT EXISTS idx_knowledge_doc_file_hash ON knowledge_documents(file_hash);
    END IF;
END $$;
