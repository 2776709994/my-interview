# TalentPilot

> 🎯 基于 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) 的 Maven 复刻与优化实践项目，融合 RAG 知识库问答与 AI 多维度评估的全栈应用。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--M4-blue.svg)](https://spring.io/projects/spring-ai)
[![React](https://img.shields.io/badge/React-18-blue.svg)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-blue.svg)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)

---

## 📖 项目简介

TalentPilot 是一个面向求职者的全栈 AI 应用，集成 **AI 简历分析**、**文字/语音模拟面试**、**知识库 RAG 问答**、**面试日程管理** 等核心能力，帮助求职者全方位提升面试表现。

### 核心功能

1. 📄 **智能简历分析**：上传 PDF/Word 简历，AI 从项目深度、技能匹配、内容完整性等 5 个维度进行评分，并给出可执行的改进建议
2. 🎤 **文字模拟面试**：基于简历 + JD + 技能方向（可选知识库参考）生成个性化面试题，支持断点续答，交卷后异步生成多维度评估报告
3. 🗣️ **语音模拟面试**：WebSocket + Qwen3 实时语音模型（ASR/TTS）实现沉浸式语音对话，支持实时字幕、回声防护、暂停/恢复、异步评估
4. 🔗 **RAG 与面试打通**：出题时向量检索所选知识库内容作为参考，让面试题贴合你的知识库资料
5. 📚 **知识库 RAG 问答**：上传技术文档构建个人知识库，基于 pgvector 向量检索 + 流式生成，实现"上传即问即答"
6. 💬 **多轮对话**：支持会话管理、消息历史、知识库关联，SSE 流式输出体验丝滑
7. 📅 **面试日程管理**：日历视图管理面试安排，支持状态自动流转（待面试 → 进行中 → 已完成）
8. ⚙️ **LLM Provider 管理**：多模型服务配置、连通性测试、默认模型切换，支持动态切换 AI 提供商

### 与原项目的差异

本项目在原项目基础上进行了深度工程化优化：

| 维度 | 优化内容 |
|------|---------|
| 框架升级 | Spring Boot 4.0.1 + Spring AI 2.0.0-M4，拥抱最新生态 |
| 并发模型 | Java 21 虚拟线程（Virtual Threads），替代传统线程池，IO 密集型场景性能大幅提升 |
| 代码质量 | 枚举替代魔法字符串、统一异常处理 + 错误码体系、统一响应格式 |
| 异步架构 | Redis Stream 实现可靠的任务队列，支持消费者组、ACK 确认、断点续传 |
| AI 架构 | LLM Provider 多模型管理、API Key 加密存储、Prompt Injection 防护 |
| 容错机制 | AI 调用指数退避重试、PDF 解析超时控制、虚拟线程 Executor 优雅关闭 |
| 工程实践 | Docker Compose 一键部署、环境变量配置化、HNSW 向量索引加速 |
| 语音面试 | 基于 Qwen3 实时语音模型（ASR/TTS），WebSocket 实时双向音频流 + 句子级并发 TTS |
| RAG 打通 | 模拟面试出题接入知识库向量检索（源项目 TODO 中未实现的"打通模拟面试和知识库"） |

---

## 🛠️ 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 编程语言（启用虚拟线程） |
| Spring Boot | 4.0.1 | Web 框架 |
| Spring AI | 2.0.0-M4 | AI 集成（OpenAI 兼容模式，通过 DashScope 调用通义千问） |
| MyBatis-Plus | 3.5.16 | ORM 框架（spring-boot4-starter） |
| PostgreSQL | 16 | 关系数据库 |
| pgvector | - | 向量存储与相似度检索（1024 维，HNSW 索引） |
| Redis | 7 | 缓存 + Stream 消息队列 |
| MinIO | 8.6.0 | 对象存储（简历/文档文件） |
| Apache Tika | 2.9.2 | 文档内容解析（PDF/Word/TXT） |
| DashScope SDK | 2.22.7 | 语音识别/合成（Qwen3 ASR/TTS Realtime） |
| WebSocket | - | 语音面试实时双向通信 |
| Lombok | - | 代码简化 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18.3 | UI 框架 |
| TypeScript | 5.6 | 类型安全 |
| Vite | 5.4 | 构建工具 |
| Tailwind CSS | 4.1 | 原子化样式 |
| React Router | 7.11 | 路由管理 |
| Framer Motion | 12.x | 动画库 |
| React Markdown | 9.0 | Markdown 渲染 |
| Recharts | 3.6 | 图表库（评估报告可视化） |
| pnpm | 10.26 | 包管理器 |

### 基础设施

| 技术 | 用途 |
|------|------|
| Docker | 容器化部署 |
| Docker Compose | 多服务编排 |
| pgvector/pgvector:pg16 | 带 pgvector 插件的 PostgreSQL 镜像 |

---

## 🏗️ 系统架构

### 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                    Frontend (React + Vite)                    │
│   简历管理 │ 模拟面试 │ 知识库管理 │ RAG 对话 │ 系统设置     │
└────────────────────────┬─────────────────────────────────────┘
                         │ HTTP / SSE
┌────────────────────────▼─────────────────────────────────────┐
│                  Backend (Spring Boot 4.0)                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Controller 层  (REST API + SSE 流式接口)              │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Service 层     (业务逻辑 + AI Prompt 编排)            │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Infrastructure (MinIO / Redis Stream / ChatClient)    │  │
│  │                 (LLM Provider Registry / 虚拟线程)      │  │
│  └────────────────────────────────────────────────────────┘  │
└──────┬──────────────────┬────────────────────┬───────────────┘
       │                  │                    │
  ┌────▼──────┐    ┌──────▼──────┐     ┌──────▼──────┐
  │ PostgreSQL │    │   Redis     │     │   MinIO     │
  │ + pgvector │    │   Stream    │     │  对象存储   │
  └───────────┘    └─────────────┘     └─────────────┘
```

### 异步任务处理流程

```
API 请求  ──▶  RedisStreamProducer  ──▶  [Redis Stream]  ──▶  Consumer 监听
                                                                  │
                                                                  ▼
                                                          虚拟线程异步执行
                                                          (AI 调用 / 文件解析)
                                                                  │
                                                                  ▼
                                                          结果写入数据库
                                                          + ACK 确认消息
```

**三条异步任务管道**：
- `resume:analysis` → 简历文本解析 + AI 多维度评分
- `interview:evaluation` → 文字面试答案 AI 评估 + 报告生成
- `voice-interview:evaluation` → 语音面试记录 AI 评估 + 报告生成

---

## 📁 项目结构

```
my-interview/
├── app/                              # Spring Boot 后端
│   ├── src/main/java/com/edu/muc/app/
│   │   ├── common/                   # 通用模块
│   │   │   ├── ai/                   # LlmProviderRegistry, StructuredOutputInvoker, PromptSanitizer
│   │   │   ├── config/               # AiConfig, ThreadPoolConfig (虚拟线程)
│   │   │   ├── constant/             # 常量定义
│   │   │   ├── evaluation/           # 统一评估服务
│   │   │   ├── exception/            # BusinessException, GlobalExceptionHandler, ErrorCode
│   │   │   ├── model/                # 通用模型 (AsyncTaskStatus)
│   │   │   ├── Result.java           # 统一响应体
│   │   │   └── JsonUtils.java        # JSON 工具类
│   │   ├── infrastructure/           # 基础设施层
│   │   │   ├── file/                 # FileStorageService + MinioFileStorageService
│   │   │   └── redis/                # RedisConfig + RedisStreamProducer
│   │   └── modules/                  # 业务模块
│   │       ├── resume/               # 简历管理（上传→异步分析）
│   │       ├── interview/            # 模拟面试（出题→答题→异步评估，RAG 打通）
│   │       ├── interviewschedule/    # 面试日程（日历管理→状态流转）
│   │       ├── knowledgebase/        # 知识库（文档→分块→向量化→检索）
│   │       ├── ragchat/              # RAG 多轮对话（SSE 流式）
│   │       ├── llmprovider/          # LLM Provider 管理（CRUD→连通性测试）
│   │       └── voiceinterview/       # 语音面试（ASR/TTS/LLM + WebSocket）
│   └── src/main/resources/
│       ├── prompts/                  # AI 提示词模板（.st 文件）
│       ├── mapper/                   # MyBatis XML 映射
│       └── application.yml           # 配置文件
│
├── frontend/                         # React 前端
│   └── src/
│       ├── api/                      # API 接口封装
│       ├── components/               # 公共组件
│       ├── pages/                    # 页面组件
│       ├── hooks/                    # 自定义 Hooks
│       ├── types/                    # TypeScript 类型
│       └── utils/                    # 工具函数
│
├── docker/
│   └── postgres/
│       ├── init.sql                  # 数据库初始化脚本
│       └── migrations/               # 增量迁移脚本
├── docker-compose.yml                # 服务编排
└── README.md
```

---

## 🚀 快速开始

### 前置要求

1. **Java 21+**（推荐 Eclipse Temurin 发行版）
2. **Node.js 18+** 和 **pnpm**
3. **Docker & Docker Compose**
4. **DashScope API Key**（[获取地址](https://dashscope.console.aliyun.com/)）

### 1. 克隆项目

```bash
git clone https://github.com/<your-username>/my-interview.git
cd my-interview
```

### 2. 配置环境变量

在项目根目录创建 `.env` 文件：

```bash
# 必填：DashScope API Key
DASHSCOPE_API_KEY=your-api-key-here

# 可选：AI 模型（默认 glm-5.2）
AI_MODEL=glm-5.2

# 可选：语音识别 / 语音合成模型（默认使用 DashScope 通义千问实时语音模型）
AI_ASR_MODEL=qwen3-asr-flash-realtime
AI_TTS_MODEL=qwen3-tts-flash-realtime
AI_TTS_VOICE=Cherry

# 可选：数据库密码（默认 password）
DB_PASSWORD=password

# 可选：面试配置
APP_INTERVIEW_FOLLOW_UP_COUNT=2
APP_INTERVIEW_EVALUATION_BATCH_SIZE=8
```

### 3. 启动后端服务（Docker 一键部署）

```bash
# 启动 PostgreSQL + Redis + MinIO + 后端应用
docker-compose up -d

# 查看后端日志
docker-compose logs -f app
```

启动完成后：
- 后端 API：http://localhost:8080
- MinIO 控制台：http://localhost:9003 （账号/密码：minioadmin/minioadmin）

### 4. 启动前端开发服务器

```bash
cd frontend
pnpm install
pnpm dev
```

访问 http://localhost:5173 即可使用。

### 5. 本地开发后端（可选）

如果不使用 Docker 启动后端，可以本地运行：

```bash
cd app
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

> 注意：本地运行后端时，需要先单独启动 PostgreSQL、Redis、MinIO（可仅运行 `docker-compose up -d postgres redis minio createbuckets`）。

---

## ⚙️ 环境变量说明

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `DASHSCOPE_API_KEY` | DashScope API Key（**必填**，同时用于 AI 对话与语音 ASR/TTS） | - |
| `AI_MODEL` | AI 对话模型 | `glm-5.2` |
| `AI_ASR_MODEL` | 语音面试识别模型（Qwen3 ASR） | `qwen3-asr-flash-realtime` |
| `AI_TTS_MODEL` | 语音面试合成模型（Qwen3 TTS） | `qwen3-tts-flash-realtime` |
| `AI_TTS_VOICE` | 语音面试音色 | `Cherry` |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库凭证 | `postgres` / `password` |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址 | `localhost` / `6379` |
| `MINIO_ENDPOINT` | MinIO 地址 | `http://localhost:9002` |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 凭证 | `minioadmin` / `minioadmin` |
| `MINIO_BUCKET` | MinIO 存储桶名 | `my-interview` |
| `APP_INTERVIEW_FOLLOW_UP_COUNT` | 面试追问次数 | `2` |
| `APP_INTERVIEW_EVALUATION_BATCH_SIZE` | 评估批量大小 | `8` |

---

## 📦 核心模块说明

### 1. 简历模块（Resume）

**流程**：上传 → MD5 查重 → MinIO 存储 → Redis Stream 异步任务 → Tika 解析 → AI 五维评分

**AI 评分维度**（总分 100）：
1. 项目经验（40 分）：技术深度、业务闭环、量化产出
2. 技能匹配（20 分）：技术栈专业度
3. 内容完整性（15 分）：模块齐全度
4. 结构清晰度（15 分）：格式规范
5. 表达专业性（10 分）：语言精炼度

**特性**：MD5 去重、30 秒解析超时、AI 调用 3 次指数退避重试、分析状态机（PENDING/PROCESSING/COMPLETED/FAILED）。

### 2. 面试模块（Interview）

**流程**：创建会话 → AI 生成题目（基于简历 + JD + 技能方向 + 可选知识库）→ 逐题答题 → 交卷 → Redis Stream 异步评估 → 生成报告

**问题分布**：50% 技术核心 + 30% 项目经验 + 20% 系统设计

**特性**：
1. 断点续答：支持暂存答案、查找未完成会话
2. 白卷处理：未答题时生成建设性的零分报告
3. 多维度报告：总分 + 优点 + 改进项 + 分类评分 + 参考答案
4. topicSummary 去重：避免历史面试中重复出题
5. **RAG 打通**：创建会话时可关联知识库，出题时对简历+JD 做向量化查询，从知识库检索相关片段注入提示词的 `referenceSection`，让面试题更贴合你的知识库资料（检索失败自动降级，不影响出题）

### 3. 语音面试模块（VoiceInterview）

**流程**：创建会话 → WebSocket 建立实时连接 → 麦克风音频流 → Qwen3 ASR 语音识别（服务端 VAD 自动断句）→ LLM 生成回答 → 句子级并发 TTS 合成 → 音频/字幕实时回传 → 结束会话 → 异步评估生成报告

**架构**：`VoiceInterviewWebSocketHandler`（`/ws/voice-interview/{sessionId}`）驱动全链路，采用虚拟线程执行阻塞的 LLM/TTS 调用。

**特性**：
1. 实时流式对话：句子级并发 TTS，边生成边合成边播放
2. 服务端 VAD：自动断句，实时字幕（含中间结果）
3. 回声防护 + 手动提交：AI 播放期间丢弃麦克风输入，防止回声误录入
4. 多轮上下文记忆 + 暂停/恢复：超时自动暂停，支持历史记录继续面试
5. 异步评估：结束后通过 Redis Stream 生成报告，前端轮询获取结果

> **已知限制**：无耳机时可能产生回声；TTS 音色固定为 Cherry；弱网下音频可能断续。

### 4. 面试日程模块（InterviewSchedule）

**流程**：创建日程 → 日历展示 → 状态自动流转（PENDING → IN_PROGRESS → COMPLETED）

**特性**：日历视图、面试信息解析（公司/职位/时间/轮次）、状态定时更新。

### 5. 知识库模块（KnowledgeBase）

**流程**：上传文档 → Tika 解析 → 智能分块（800 字符/块，150 字符重叠）→ 向量化（1024 维）→ pgvector 存储 → 余弦相似度检索

**父子文档设计**：
- 父文档：存储完整内容，`parent_id = NULL`
- 子文档：存储分块内容 + 向量，`parent_id` 指向父文档

**检索策略**：初始召回 Top-15 → 相似度阈值过滤（0.3）→ 最终返回 Top-5

### 6. RAG 聊天模块（RagChat）

**流程**：用户提问 → 问题改写（防 Prompt Injection）→ 向量化检索 → 构建 Prompt → SSE 流式生成 → 持久化消息

**特性**：多轮对话、会话置顶、知识库关联、流式输出、提问/访问统计。

### 7. LLM Provider 管理模块

**流程**：配置 Provider（Base URL + API Key + Model）→ 加密存储 → 连通性测试 → 设为默认

**特性**：
1. 多 Provider 管理：支持配置多个 AI 服务提供商
2. API Key 加密：AES 加密存储，运行时解密
3. 连通性测试：一键验证 Provider 可用性
4. 动态切换：运行时切换默认 Chat/Embedding Provider
5. 启动自动加载：应用启动时从数据库加载 Provider 配置

---

## 🗄️ 数据库设计

数据库初始化脚本位于 `docker/postgres/init.sql`，核心表结构：

| 表名 | 说明 |
|------|------|
| `resumes` | 简历主表（文件元数据 + 解析文本 + 分析状态） |
| `resume_analyses` | 简历分析结果（5 维评分 + 优点 + 建议） |
| `interview_sessions` | 面试会话（技能方向 + 进度 + 评估状态 + `knowledge_base_ids` 关联知识库） |
| `interview_questions` | 面试题目（问题 + 类型 + 类别 + 参考答案） |
| `interview_answers` | 用户答案（回答 + 评分 + AI 反馈） |
| `interview_schedule` | 面试日程（公司 + 职位 + 时间 + 状态） |
| `knowledge_documents` | 知识文档（父子结构 + 向量字段 `vector(1024)`） |
| `vector_store` | Spring AI 向量存储（自动管理） |
| `chat_sessions` | RAG 聊天会话 |
| `chat_messages` | RAG 聊天消息 |
| `llm_provider_config` | LLM Provider 配置（加密 API Key） |
| `llm_global_setting` | LLM 全局设置（默认 Provider） |
| `voice_interview_sessions` | 语音面试会话（阶段 + 状态 + 计划时长） |
| `voice_interview_messages` | 语音面试对话消息（用户语音识别文本 + AI 回复文本） |
| `voice_interview_evaluations` | 语音面试评估报告（逐题评估 + 优点 + 改进建议） |

**向量索引**：`knowledge_documents.content_embedding` 使用 HNSW 索引加速余弦相似度检索：

```sql
CREATE INDEX idx_knowledge_doc_embedding_hnsw
ON knowledge_documents
USING hnsw (content_embedding vector_cosine_ops);
```

---

## 🔧 开发指南

### 后端开发

**添加新的异步任务**：

1. 在 `RedisStreamProducer` 中添加发送方法
2. 创建 `@Component` 消费者，实现 `@PostConstruct` 启动监听线程
3. 使用 `@Qualifier` 注入对应的虚拟线程 Executor
4. 实现 `@PreDestroy` 优雅关闭

**添加新模块**：

遵循分层结构 `controller → service(impl) → mapper`，实体放 `domain/`，传输对象放 `dto/`。

### 前端开发

1. API 调用统一通过 `@/api/request.ts` 封装的 axios 实例
2. 页面组件放 `pages/`，公共组件放 `components/`
3. 路由配置在 `App.tsx`

### 常用命令

```bash
# 后端
cd app
./mvnw compile                  # 编译
./mvnw test                     # 测试
./mvnw package -DskipTests      # 打包

# 前端
cd frontend
pnpm install                    # 安装依赖
pnpm dev                        # 开发服务器
pnpm build                      # 构建

# Docker
docker-compose up -d            # 启动全部服务
docker-compose logs -f app      # 查看后端日志
docker-compose down -v          # 停止并清除数据
```

---

## 🐛 常见问题

### 1. AI 调用超时

**症状**：`Read timed out` 或分析任务长时间停留在 PROCESSING

**解决**：
1. 检查 `DASHSCOPE_API_KEY` 是否正确
2. 检查网络是否能访问 DashScope API
3. 后端已内置 3 次指数退避重试（2s → 4s → 8s）

### 2. PDF 解析结果为空

**症状**：简历分析报错"PDF 解析结果为空"

**原因**：PDF 是扫描件（图片格式），Tika 无法提取文字

**解决**：使用 Adobe Acrobat 的 OCR 功能，或找到原始 Word 文档另存为 PDF

### 3. Redis 连接失败

**症状**：`RedisConnectionFailureException`

**解决**：
```bash
docker ps | grep redis          # 检查 Redis 是否运行
docker restart interview-redis2 # 重启 Redis
```

### 4. 向量维度不匹配

**症状**：`expected 1024 dimensions, not 1536`

**解决**：
```bash
# 清除旧向量数据并重建
docker exec <postgres-container> psql -U postgres -d interview_guide -c \
  "DROP INDEX IF EXISTS idx_knowledge_doc_embedding_hnsw;
   ALTER TABLE knowledge_documents ALTER COLUMN content_embedding TYPE vector(1024) USING NULL;
   UPDATE knowledge_documents SET content_embedding = NULL, vector_status = 'PENDING';
   CREATE INDEX idx_knowledge_doc_embedding_hnsw ON knowledge_documents USING hnsw (content_embedding vector_cosine_ops);"
```

### 5. 前端白屏

```bash
cd frontend
rm -rf node_modules pnpm-lock.yaml
pnpm install
pnpm dev
```

### 6. 语音面试无法识别或没有声音

**可能原因与解决**：
1. 检查浏览器是否授予了麦克风权限，建议佩戴耳机测试避免回声
2. 语音面试的 ASR/TTS 使用 `DASHSCOPE_API_KEY`，确认其有效（与 AI 对话共用同一个 Key）
3. 查看后端日志中的 DashScope WebSocket 连接状态，确认 `qwen3-asr-flash-realtime` / `qwen3-tts-flash-realtime` 模型可访问
4. 弱网环境可能出现音频断续，可稍后重试

### 7. 语音面试无法建立 WebSocket 连接

**检查项**：
1. 确认后端已启动，`/ws/voice-interview/{sessionId}` 可访问
2. 若使用 Nginx 反向代理，需配置 WebSocket 升级头（`Upgrade` / `Connection`）与长连接超时
3. 检查是否被浏览器混合内容策略拦截（页面为 HTTPS 时 WebSocket 需为 `wss://`）

---

## 📈 性能优化

1. **虚拟线程**：Java 21 Virtual Threads 替代传统线程池，IO 密集型任务并发能力大幅提升
2. **N+1 查询优化**：列表接口批量查询关联数据
3. **向量检索加速**：HNSW 索引 + 相似度阈值过滤
4. **异步处理**：耗时 AI 任务通过 Redis Stream 异步化
5. **流式响应**：SSE 实时推送，减少用户等待感
6. **连接池**：数据库、Redis、AI 客户端均配置连接池

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m 'feat: add your feature'`
4. 推送分支：`git push origin feature/your-feature`
5. 开启 Pull Request

---

## 📄 许可证

本项目遵循 **AGPL-3.0 License**（与原始项目保持一致），详见 [LICENSE](./LICENSE) 文件。

---

## 🙏 致谢

本项目基于以下开源项目开发：

1. **[Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide)** — 智能面试助手原始项目（AGPL-3.0）
2. [Spring Boot](https://spring.io/projects/spring-boot)
3. [Spring AI](https://spring.io/projects/spring-ai)
4. [React](https://react.dev/)
5. [通义千问 / DashScope](https://dashscope.console.aliyun.com/)
6. [pgvector](https://github.com/pgvector/pgvector)

感谢原作者 **Snailclimb** 的开源贡献！

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐ Star！**

Made with ❤️

</div>
