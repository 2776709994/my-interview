-- 语音面试模块增量迁移脚本
-- 为存量数据库补齐 voice_interview_* 三张表（与 init.sql 定义一致）
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
