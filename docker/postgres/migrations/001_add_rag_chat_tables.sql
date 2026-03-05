-- RAG 模块数据库迁移脚本
-- 执行时间: 2026-04-30

-- 1. 为 chat_sessions 表添加缺失字段
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='chat_sessions' AND column_name='is_pinned') THEN
        ALTER TABLE chat_sessions ADD COLUMN is_pinned BOOLEAN DEFAULT FALSE;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='chat_sessions' AND column_name='updated_at') THEN
        ALTER TABLE chat_sessions ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
    END IF;
END $$;

-- 2. 创建 chat_messages 表
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT REFERENCES chat_sessions(id) ON DELETE CASCADE,
    type VARCHAR(20),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 为 chat_messages.session_id 添加索引以提升查询性能
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages(session_id);
