-- 面试模块增量迁移脚本：为存量 interview_sessions 表补齐缺失列
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='interview_sessions' AND column_name='knowledge_base_ids') THEN
        ALTER TABLE interview_sessions ADD COLUMN knowledge_base_ids TEXT;
    END IF;
END $$;
