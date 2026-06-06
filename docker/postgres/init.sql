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
    file_hash VARCHAR(64),
    vector_status VARCHAR(50) DEFAULT 'PENDING',
    vector_error TEXT,
    chunk_count INTEGER DEFAULT 1,
    question_count INTEGER DEFAULT 0,
    access_count INTEGER DEFAULT 0,
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    last_accessed_at TIMESTAMP,
    -- 父子文档结构：父文档为原始文件（parent_id=NULL，chunk_index=-1），子文档为分块（parent_id=父文档ID，chunk_index=分块序号）
    parent_id BIGINT,
    chunk_index INTEGER DEFAULT -1
);

-- 为向量列创建 HNSW 索引（加速余弦相似度检索）
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_embedding_hnsw 
ON knowledge_documents 
USING hnsw (content_embedding vector_cosine_ops);

-- 文件哈希索引（MD5 查重）
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_file_hash ON knowledge_documents(file_hash);

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

-- ==================== 模拟面试（文字）表 ====================
CREATE TABLE IF NOT EXISTS interview_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    resume_id BIGINT,
    resume_text TEXT,
    jd_text TEXT,
    knowledge_base_ids TEXT,
    skill_id VARCHAR(64),
    difficulty VARCHAR(16),
    total_questions INTEGER,
    current_question_index INTEGER DEFAULT 0,
    status VARCHAR(20),
    evaluate_status VARCHAR(20),
    evaluate_error TEXT,
    overall_score INTEGER,
    overall_feedback TEXT,
    strengths_json TEXT,
    improvements_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    evaluated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_questions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64),
    question_index INTEGER,
    question TEXT,
    type VARCHAR(50),
    category VARCHAR(100),
    topic_summary VARCHAR(255),
    reference_answer TEXT,
    key_points_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_interview_questions_session ON interview_questions(session_id);

CREATE TABLE IF NOT EXISTS interview_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64),
    question_index INTEGER,
    answer TEXT,
    score INTEGER,
    feedback TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    evaluated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_interview_answers_session ON interview_answers(session_id);

-- ==================== 语音面试表 ====================
CREATE TABLE IF NOT EXISTS voice_interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64),
    role_type VARCHAR(64) NOT NULL,
    skill_id VARCHAR(64) DEFAULT 'java-backend',
    difficulty VARCHAR(16) DEFAULT 'mid',
    custom_jd_text TEXT,
    resume_id BIGINT,
    intro_enabled BOOLEAN DEFAULT TRUE,
    tech_enabled BOOLEAN DEFAULT TRUE,
    project_enabled BOOLEAN DEFAULT TRUE,
    hr_enabled BOOLEAN DEFAULT TRUE,
    llm_provider VARCHAR(50) DEFAULT 'dashscope',
    current_phase VARCHAR(20),
    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
    planned_duration INTEGER DEFAULT 30,
    actual_duration INTEGER,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    paused_at TIMESTAMP,
    resumed_at TIMESTAMP,
    evaluate_status VARCHAR(20),
    evaluate_error VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_voice_session_user ON voice_interview_sessions(user_id, updated_at);

CREATE TABLE IF NOT EXISTS voice_interview_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT,
    message_type VARCHAR(30) NOT NULL,
    phase VARCHAR(20),
    user_recognized_text TEXT,
    ai_generated_text TEXT,
    timestamp TIMESTAMP,
    sequence_num INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_voice_message_session ON voice_interview_messages(session_id, sequence_num);

CREATE TABLE IF NOT EXISTS voice_interview_evaluations (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT UNIQUE,
    overall_score INTEGER,
    overall_feedback TEXT,
    question_evaluations_json TEXT,
    strengths_json TEXT,
    improvements_json TEXT,
    reference_answers_json TEXT,
    interviewer_role VARCHAR(100),
    interview_date TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
