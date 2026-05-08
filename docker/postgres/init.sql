-- 启用 pgvector 扩展（用于 RAG 知识库向量检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- 知识文档表
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    category VARCHAR(100),
    file_name VARCHAR(255) NOT NULL,
    content TEXT,
    content_embedding vector(1024),
    file_size BIGINT,
    content_type VARCHAR(100),
    storage_key VARCHAR(255),
    storage_url TEXT,
    vector_status VARCHAR(50) DEFAULT 'PENDING',
    vector_error TEXT,
    chunk_count INTEGER DEFAULT 1,
    question_count INTEGER DEFAULT 0,
    access_count INTEGER DEFAULT 0,
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    last_accessed_at TIMESTAMP
);

-- 为向量列创建 HNSW 索引（加速余弦相似度检索）
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_embedding_hnsw 
ON knowledge_documents 
USING hnsw (content_embedding vector_cosine_ops);

-- 简历表
CREATE TABLE IF NOT EXISTS resumes (
    id BIGSERIAL PRIMARY KEY,
    access_count INTEGER DEFAULT 0,
    analyze_error TEXT,
    analyze_status VARCHAR(50),
    content_type VARCHAR(100),
    file_hash VARCHAR(64),
    file_size BIGINT,
    last_accessed_at TIMESTAMP,
    original_filename VARCHAR(255) DEFAULT '未命名简历',
    resume_text TEXT,
    storage_key VARCHAR(255),
    storage_url TEXT,
    uploaded_at TIMESTAMP
);

-- 简历分析结果表
CREATE TABLE IF NOT EXISTS resume_analyses (
    id BIGSERIAL PRIMARY KEY,
    analyzed_at TIMESTAMP,
    content_score INTEGER,
    expression_score INTEGER,
    overall_score INTEGER,
    project_score INTEGER,
    skill_match_score INTEGER,
    strengths_json TEXT,
    structure_score INTEGER,
    suggestions_json TEXT,
    summary TEXT,
    resume_id BIGINT REFERENCES resumes(id) ON DELETE CASCADE
);

-- RAG 聊天会话表
CREATE TABLE IF NOT EXISTS chat_sessions (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255),
    knowledge_base_ids TEXT,
    is_pinned BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- RAG 聊天消息表
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT REFERENCES chat_sessions(id) ON DELETE CASCADE,
    type VARCHAR(20),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 面试日程表
CREATE TABLE IF NOT EXISTS interview_schedule (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL,
    position VARCHAR(255) NOT NULL,
    interview_time TIMESTAMP NOT NULL,
    interview_type VARCHAR(50),
    meeting_link TEXT,
    round_number INTEGER DEFAULT 1,
    interviewer VARCHAR(255),
    notes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_interview_schedule_status ON interview_schedule(status);
CREATE INDEX IF NOT EXISTS idx_interview_schedule_time ON interview_schedule(interview_time);

-- LLM Provider 配置表
CREATE TABLE IF NOT EXISTS llm_provider_config (
    id VARCHAR(64) PRIMARY KEY,
    base_url VARCHAR(512) NOT NULL,
    api_key_ciphertext VARCHAR(4096) NOT NULL,
    api_key_nonce VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(128),
    embedding_dimensions INTEGER,
    supports_embedding BOOLEAN NOT NULL DEFAULT FALSE,
    temperature DOUBLE PRECISION,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- LLM 全局设置表（单例）
CREATE TABLE IF NOT EXISTS llm_global_setting (
    id BIGINT PRIMARY KEY,
    default_chat_provider_id VARCHAR(64) NOT NULL,
    default_embedding_provider_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
