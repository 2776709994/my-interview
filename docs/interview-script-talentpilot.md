# TalentPilot 面试讲稿（v1 · 基于真实代码 commit c9cfb4a）

> 本文所有技术细节均来自仓库真实代码，已逐文件核对：
> ResumeController / ResumesServiceImpl / RedisStreamProducer / StreamPendingRecoverer /
> KnowledgeDocumentServiceImpl / SmartRetrievalServiceImpl / VoiceInterviewWebSocketHandler /
> DashscopeLlmService / QwenTtsService / application.yml / eval/output/compare_output.log
> 面试前必须把"【背诵】"标记的段落背熟，其余理解即可。

---

## 0. 开场 30 秒（定调）

> "我 5 月到 8 月开发了 TalentPilot，一个基于 Spring AI 的求职辅助平台：简历智能分析、知识库 RAG 问答、文字 + 语音模拟面试。后端 Java 21 + Spring Boot 4 + Spring AI，向量存 PostgreSQL pgvector，耗时任务用 Redis Stream 异步解耦，文件存 MinIO，Docker Compose 一键部署 6 个服务。
> 技术上我最想讲的两块：一是 RAG 检索用 RAGAS 实测调参（Top-10 召回 → 阈值过滤 → Top-5 精取，0.9354）；二是语音面试的句子级并发 TTS——LLM 边生成边按句合成，第一句还没说完，第二句已经在合成了。"

【注意】时间线说 **5 月到 8 月**（git 首提交 2026-05-04，末提交 08-14）。绝不说 3 月。

---

## 1. 项目来源话术（必背，第一刀）

> "这个项目基于 Snailclimb 的 interview-guide 二次开发，我 README 里如实标注了。分两个阶段：
> - **第一阶段（5 月）**：借它的工程骨架和选型（Spring AI + pgvector + Redis Stream），核心工作是把持久层从 JPA 全量重写成 MyBatis-Plus，构建从 Gradle 迁到 Maven，还自己写了 Stream 的 PEL 恢复器（源项目没有这个类）。
> - **第二阶段（7–8 月）**：和源项目做了一次版本对齐，整合了 LLM Provider 管理和语音面试模块，然后自己做了适配改造：语音链路迁到虚拟线程执行器、修了 WebSocket 地址写死 localhost 的问题、加了开场白音频预热缓存和 ASR 断线自动重连。
> - RAG 的 RAGAS 调参、模拟面试打通知识库出题、异步评估报告，是我从头到尾自己做的。
> 您随便挑一个模块，我可以从数据结构讲到设计动机。"

【铁律】
- 绝不说"源项目当时是空壳/TODO"（源项目 2025-12-26 就开始开发，2026-01 已有 RAG 和 Stream 异步，3 月已功能完整）。
- 绝不说"语音面试是我从零设计的"（源项目 2026-04-11 就有"句子级并发 TTS"，你 8 月才合入）。
- 绝不说"个人独立开发"（你的 prep 文档 Q1 写错了，README 写的是"复刻"）。
- 被问"哪些是 AI 写的"：坦然说"工程上用 AI 辅助编码，但每行代码我都读过、能讲明白——您现在就可以考我"。

---

## 2. 简历模块（上传 → 异步分析）

【背诵】真实链路（ResumesServiceImpl.upload，@Transactional）：
1. 算文件 MD5（`MessageDigest`，全量读进内存）；
2. 查 `resumes.file_hash` 是否已存在 → 存在就直接 XADD 一条"重新分析"任务、返回旧记录（不重复存文件）；
3. 新文件：MinIO `putObject`（`FileStorageService.store`）→ 拿 URL → INSERT resumes（状态 PENDING）；
4. `RedisStreamProducer.sendResumeAnalysisTask(id)`：XADD 到 `resume:analysis` stream，再 trim 裁剪（MAXLEN）防膨胀；—— **新文件分支在事务提交后（afterCommit）才发送**（见下"事务里 XADD 的问题"），避免消费端读到未提交数据；
5. 返回。**接口耗时大头是 MinIO 上传（10~50ms），XADD 只有 1~3ms。**
6. 消费者（ResumeAnalysisConsumer）：读消息 → Tika 解析简历文本（30s 超时）→ ChatClient 五维评分（项目 40/技能 20/完整 15/结构 15/表达 10）→ 写 resume_analyses → 更新状态 → XACK。AI 调用失败指数退避重试 3 次（2s→4s→8s）。

【追问防守】
- 问"为什么 MD5 查重"：防重复解析/向量化，省钱省时（每次解析都是 LLM 调用）。
- 问"MD5 有冲突/并发上传怎么办"：MD5 碰撞概率可忽略；同一文件并发上传可能都走到 INSERT（没有唯一索引），这是已知弱点——面试可主动说"可以给 file_hash 加唯一索引 + 捕获冲突"。
- 问"事务里 XADD 的问题"（加分题，真实踩坑 + 已修复）：原实现 XADD 在 @Transactional 内，消息先于事务提交被消费者读到 → 消费者 `selectById` 查不到未提交的行（PostgreSQL READ COMMITTED）→ 原代码"查不到就 log + return"，上层视为成功 → **XACK 掉消息 → 任务永久丢失，简历永远卡 PENDING**（不是"进 PEL 自愈"！）。真实案例：一次连传 3 份简历全部卡 PENDING，日志里入库与消费端查询的时间戳只差 2~4ms。修复双保险：① 生产端用 `TransactionSynchronization.afterCommit` 在事务提交后再 XADD；② 消费端查不到记录改为抛异常，消息留在 PEL → `StreamPendingRecoverer` 5 分钟重投 → 投递 ≥3 次死信。主动讲出来 = 你懂一致性和失败场景设计。

---

## 3. Redis Stream 异步任务（重点模块）

【背诵】三个 Stream：`resume:analysis`、`interview:evaluation`、`voice-interview:evaluation`。

**生产者（RedisStreamProducer）**：`addAndTrim` = XADD + trim(MAXLEN)。每个任务只是一条消息（resumeId / sessionId），**不携带重数据**。

**消费者**：`XREADGROUP` 消费组阻塞读（组内一条消息只投给一个消费者，多实例不重复）→ 虚拟线程执行（`spring.threads.virtual.enabled=true`）→ 处理成功后 `XACK`。

**PEL 恢复器（StreamPendingRecoverer，你自己写的，重点讲）**：
- 问题：消费者崩溃/处理失败 → 消息滞留 PEL，且 XREADGROUP 只投新消息，不会自动重投；
- 方案：定时（消费者里 @Scheduled）调用 `recover()`：`pending(streamKey, group, Range.unbounded(), 100)` 扫描 PEL；
  - idle < 5 分钟（MIN_IDLE）→ 跳过（还在处理中）；
  - **idle ≥ 5 分钟 且投递次数 < 3 → `claim` 到当前消费者重新处理**（超时重投）；
  - **投递 ≥ 3 次仍失败 → 直接 ACK 丢弃（等效死信）**，避免无限重试；
  - 重投处理成功 → ACK；失败 → 留在 PEL 等下轮。
- 为什么 MIN_IDLE=5min：必须大于单任务最长处理时间，否则正在慢处理的消息会被误 claim 重复执行。

【追问防守】
- 问"消息重复处理怎么办"：① 消费组保证一条消息只投给组内一个消费者；② claim 重投可能重复执行 → 处理逻辑幂等——评估结果写入前校验状态，只有 PROCESSING 才能写 COMPLETED；③ 死信有上限不会无限重试。
- 问"为什么不用 Kafka/RabbitMQ"：量不大（几百/天）、延迟要低、不想加组件；Redis 本来就在技术栈里，Stream 自带消费组/PEL/ACK。Kafka 的吞吐优势用不上，还多一个集群要维护。
- 问"消息会丢吗"：Redis 持久化（RDB/AOF）保证不因重启丢；消费失败留在 PEL 可恢复；投递 3 次失败才放弃（有日志）。

---

## 4. RAG 知识库（重点模块）

【背诵】上传链路（KnowledgeDocumentServiceImpl.upload，**同步**）：
1. MD5 查重（按 file_hash + parent_id IS NULL，命中直接返回旧记录）；
2. 存 MinIO（knowledge-base 目录）；
3. Apache Tika `parseToString` 解析 PDF/Word/TXT；
4. 分块：`splitTextIntoChunks(content, 800)`——每块目标 800 字符，重叠 `min(150, chunkSize/5)`；**优先在句子边界截断**（向后最多找 100 字符：换行 > 句号 > 空格，找不到再向前找），防止切断语义；防死循环保护；
5. 建父文档（存全文 + 元数据，无向量，parent_id=NULL）→ 每个 chunk：`embeddingModel.embed(chunk)` 生成 1024 维向量 → 子文档（parent_id 关联 + chunk_index + 向量）入库。

【注意】你的向量化是**同步**的（上传请求里直接调 embedding API），源项目是异步 VectorizeStreamConsumer。被问"为什么上传慢/为什么不用异步"：
> "知识库向量化我做了同步——上传时立即完成，用户马上能问答；代价是上传接口耗时随文档大小线性增长（每个 chunk 一次 embedding 调用）。表里有 vector_status 字段，revectorize 接口已经是异步的（CompletableFuture），所以改成异步任务只需要把 upload 里的循环挪进消费者——这是我知道的优化点。"

【背诵】检索三级流水线（SmartRetrievalServiceImpl）：
1. `searchBySimilarityWithScoreAndKb(queryVector, topK=10, knowledgeBaseIds)`：SQL 按余弦距离（`<=>`）取 Top-10 候选（可按知识库 ID 过滤）；
2. 阈值过滤：`similarity_score <= 0.3`（**余弦距离**，0~2 区间，越小越相似，即相似度 ≥ 0.7）——过滤掉不相关内容；
3. **全部被过滤 → 兜底返回最相似 1 条**（保证"有上下文可用"，不空答）；
4. 取前 `finalTopK=5` 拼上下文，每条带【文档名】来源标注。

【背诵】RAG 问答（queryStream，SSE）：
- SseEmitter(180s) + 异步线程（ragQueryExecutor）→ 问题向量化 → smartRetrieve → 拼上下文（【来源】标注）→ 系统提示词"只根据上下文回答，没有相关信息要诚实告知" → `chatClient.stream()` 流式 chunk 级 `emitter.send`，失败 `completeWithError` 并取消上游订阅 → 完成后更新父文档 question_count / access_count。

**RAGAS 调参（你的亮点，必背）**：
> "我用 RAGAS 做了参数对比：25 条带标准答案的样本集，6 个指标（Faithfulness 忠实度、Context Recall、Context Precision、Answer Similarity、Answer Relevancy 等），judge LLM 用 qwen-max，对 3 组参数各跑 3 条：top-k=10/阈值0.3 → **0.9354**、15/0.25 → 0.9298、10/0.25 → 0.9210，选最优落地为线上配置（application.yml）。"
【诚实补充（必须说，否则被追问死）】：
> "局限我也清楚：每组只有 3 条样本，方向可信但不统计显著；而且评估时 embedding 用的是 text-embedding-v2，线上是 qwen3.7-text-embedding，严格说应该统一。如果重做，我会扩样本到 50+ 条并统一 embedding。"

【追问防守】
- 问"为什么 pgvector 不用 Milvus/ES"：千级 chunk 量级，pgvector HNSW 足够；与业务数据同库、支持事务和按知识库过滤、零额外组件。亿级向量/高 QPS 才需要专用向量库——"知道边界"是加分项。
- 问"HNSW 参数"：`USING hnsw (content_embedding vector_cosine_ops)`，m/ef_construction 用的默认值，没单独调——老实说，并说"如果数据量上来，ef_search 是检索延迟/召回的可调旋钮"。
- 问"分块为什么 800/150"：800 字符 ≈ 中文 400~800 token，兼顾语义完整和 embedding 精度；150 重叠（~19%）防止切分切断句子/知识点。
- 问"幻觉怎么解决"：四层——RAG 提供上下文不裸答、阈值过滤不相关内容、Prompt 约束"不知道就说不知道"、回答标注【来源】可追溯。诚实补充：无法 100% 消除，阈值越高召回越低，是权衡。

---

## 5. SSE 流式问答

【背诵】为什么 SSE 不用 WebSocket：问答是"服务端单向推流"，SSE 基于 HTTP——协议简单、自动重连、Nginx 只需 `proxy_buffering off`；WebSocket 全双工留给语音（要双向持续传音频）。实现：SseEmitter(180s) + `CompletableFuture.runAsync`（不占请求线程）+ 响应式流 chunk 级 `emitter.send`，发送失败 `completeWithError` + 取消订阅防泄漏；完整回答由服务端落库（不依赖前端回传）。

---

## 6. 语音面试（重点模块，背熟链路）

【背诵】全链路（VoiceInterviewWebSocketHandler 驱动）：
1. **连接**（/ws/voice-interview/{sessionId}）：校验会话存在且 IN_PROGRESS/PAUSED → 包 `ConcurrentWebSocketSessionDecorator`（发送超时 10s + 缓冲 512KB，防慢客户端阻塞）→ 启动 DashScope ASR 流式识别（partial/final 双回调）→ 无历史则发开场白（**开场白 TTS 音频在 @PostConstruct 预热缓存**，不用现合成）。
2. **上行**：前端麦克风 PCM 16kHz 音频 Base64 上行 → `sttService.sendAudio`。三道防线：AI 正在说话或 800ms 冷却期直接丢弃（**回声防护**）；ASR 未就绪丢弃；ASR 连接断 → 自动重启并 15 次×80ms 重试补发。
3. **识别**：ASR partial（中间结果）→ 实时字幕预览；final（定稿段）→ 进 mergeBuffer 累积。**手动提交模式**：用户点"提交"→ `flushMergedUtteranceToLlm`，`compareAndSet(false,true)` 防重入 → 虚拟线程池执行 LLM 流水线（一个会话同时只有一条流水线）。
4. **LLM（DashscopeLlmService.chatStreamSentences）**：流式 token 累积；**检测到终止标点（。！？；!?;.）→ 把完整句子回调出去**；onToken 节流推送（80ms + 4 字符差）给前端实时字幕；末尾剩余句子补发；`optimizeForVoice` 截断（默认 80 字符内找终止符，找不到补"…"）——语音不能等长文。
5. **句子级并发 TTS（你简历那句话的出处）**：
   - `triggerLlmResponse` 里 `new Semaphore(Math.max(1, maxConcurrentTtsPerSession))`，配置默认 **3**；
   - 每收到一个句子：`ttsSemaphore.acquireUninterruptibly()` → 虚拟线程池里调 `ttsService.synthesize(sentence)` → finally `release()`；
   - **为什么限 3 路**：`QwenTtsService.synthesize()` 每次都是一条新 DashScope WebSocket 连接（connect → 配置音色/采样率 → appendText → commit → 等 audio 事件 → close，30s 超时保护），不限流会打爆连接配额；3 是延迟与资源的平衡；
   - **怎么保证不乱序**：chunked 模式用内部类 `OrderedTtsChunkEmitter`——`ConcurrentHashMap<Integer, Future>` 按 index 顺序 drain（`waitForFuture` 等前面的完成再发），保证音频按句子顺序推；非 chunked 模式把各句 PCM 按序拼接成一段 WAV 一次下发；
   - **兜底**：某句失败跳过并记日志；全部失败 → 整段文本重新合成一次；流式一条 chunk 都没产出 → 降级整段 TTS。
6. **音频格式**：TTS 返回 PCM 24kHz/16bit/单声道 → 手动加 44 字节 WAV 头（RIFF/fmt/data）→ Base64 下行 → 前端播放。
7. **会话生命周期**：4:30 警告、5:00 无活动自动暂停（@Scheduled 30s 扫描）→ 断线仅 IN_PROGRESS 自动结束 → 结束触发 `VoiceEvaluateStreamProducer` 入队异步评估（复用 Redis Stream）→ 定时清理 2h 僵尸会话 + 卡死 30min 的 PROCESSING 评估。

【追问防守】
- 问"为什么 WebSocket 不用 STOMP"：原生 WebSocket 轻量、控制力强；消息协议自己定（type: audio/control/subtitle/text），不需要 STOMP 的订阅模型。
- 问"3 路限流是每会话还是全局"：**每次回复时 new 一个 Semaphore(3)，是"每次 LLM 回复的 TTS 并发上限"，不是会话级全局**——如果面试官问第 4 个句子怎么办：acquire 阻塞等待，前面的 release 后才继续，不会爆连接。
- 问"并发 TTS 省了什么时间"：朴素做法等全文生成完再合成（用户干等 5~10 秒）；句子级 = 第一句生成完就开始合成播放，同时第二句还在生成/合成——"写一句念一句"。
- 问"ASR 用的什么模型/协议"：qwen-audio-3.0-asr-flash-streaming，DashScope WebSocket（wss://dashscope.aliyuncs.com/api-ws/v1/realtime），PCM 16kHz，服务端 VAD 自动断句（silence 400ms）。

---

## 7. 模拟面试闭环（出题 → 答题 → 异步评估）

> 内容已逐行核对 InterviewServiceImpl（commit c9cfb4a）+ prompts/*.st 存在性，可直接背诵。

【背诵】创建面试：输入 = 简历（文本或 ID）+ 技能方向 + 难度 + 题数 + JD（可选）+ 知识库（可选，RAG 打通）。
出题 = 一次 AI 调用，四块输入：
1. **简历部分**（针对性提问）；
2. **JD 部分**（有 JD 时）；
3. **历史知识点**：查同一简历最近 5 次面试的题目 topicSummary，去重取前 20 个注入"已考知识点"——**防重复出题**；
4. **RAG 参考题库**：把"简历+JD"拼成查询文本（截 2000 字）→ Embedding → 从所选知识库检索片段作为出题参考（referenceSection）；检索失败静默降级为"无"，不影响出题。
外加：问题分布表（技术核心 50% / 项目经验 30% / 系统设计 20%）、当前时间（防时间幻觉）、追问轮数（默认 2）。

解析与降级：结构化输出 JSON（每题含 question/type/category/topicSummary/followUps）→ 解析失败降级为 5 道预设题，**面试永远能开始**。

答题：校验会话状态（IN_PROGRESS/CREATED）+ 题目索引防乱序越界 → 存答案 → 推进进度；最后一题自动 COMPLETED；支持提前交卷。

异步评估（UnifiedEvaluationService）：交卷 → XADD `interview:evaluation` → 消费者置 PROCESSING → **分批结构化评估**（每批 5 题一次 LLM 调用出 JSON，失败带退避重试，全批完成后再单独一次"总结"生成总评）→ 写回总分/分类分/每题分数反馈/参考答案/关键点/优势改进/总评 → COMPLETED；全部失败 → 兜底模板报告（"本次面试已完成分批评估"），**用户永远拿得到报告，只是质量降级**；PROCESSING 时查报告提示"稍后刷新"防重复触发。

【追问防守】
- 问"追问是实时的吗"：**不是**。出题时注入 followUpCount=2，AI 为每个主问题预生成 2 个追问（followUps 字段），面试中逐层展示；提示词要求追问遵循"使用经验 → 核心原理 → 边界/优化"递进。诚实说"这是静态预生成，不是实时追问——实时追问是后续优化点"。
- 问"去重怎么实现的"：每题让 AI 返回 ≤10 字知识点摘要（topicSummary）→ 下次出题前查历史去重取 20 个拼进提示词 → AI 出题时避开。诚实说"这是提示词层面的软去重，不是向量硬去重"。

---

## 8. 数字口径话术（QPS 700+ / P99 33ms）

【必背】被问"这个数字怎么来的"：
> "这个数字我简历里写得不够严谨，两个口径并在一行了。真实情况：
> - 压测用 AI 生成的脚本，100 线程无思考时间，压的是**上传简历→提交异步任务**接口；客户端观测 QPS 700+、平均响应约 140ms——两个数满足 Little's law（100 在途 ÷ 700 ≈ 143ms），自洽。
> - P99 33ms 不是客户端响应延迟，是服务端 XADD 入队段的处理耗时。两个数不是一个量的分位数，并排写造成误读，是我的表述问题。
> - 这次压测没压出服务端上限：瓶颈在压测客户端配置，服务端实际吞吐更高。重做的话我会用 JMeter 分两个接口测：纯入队延迟分布 + 含上传的端到端，再补消费者吞吐和堆积水位。"

【行动项（二选一，面试前完成）】① 用 wrk/JMeter 重跑一次留档；② 简历数字改成"提交接口仅入队即返回，100 并发下延迟 <40ms"或干脆删掉数字。
【铁律】不再说"O(1)"——XADD 官方复杂度 O(log N)（基数树），你想表达的是"提交耗时与任务复杂度无关"，就说这句话。你的接口也不是纯入队：还有 MD5 + MinIO + INSERT。

---

## 9. 雷区清单（这些说法一律禁止）

| 禁止说 | 为什么 | 改成 |
|---|---|---|
| "3 月开始做的" | git 首提交 05-04 | "5 月到 8 月" |
| "当时源项目是空壳/TODO" | 源项目 2025-12-26 起开发，3 月已完整 | "借了它的工程骨架和选型" |
| "语音面试是我从零设计" | 源项目 04-11 已有"句子级并发 TTS" | "整合源项目语音模块并做适配改造"（见 §1） |
| "RAG 是我自己构想的" | 源项目 12-31 就有 RAG | "RAG 全链路我实现并调参，RAGAS 0.9354" |
| "个人独立开发" | README 写的是"复刻" | "基于 interview-guide 二次开发" |
| "O(1) 入队即返回" | XADD 是 O(log N)，且接口含上传 | "提交耗时与任务复杂度无关" |
| "QPS 700+、P99 33ms" | 仓库无压测脚本，口径不统一 | §8 话术或删数字 |
| "RAGAS 0.9354 最优"（不带限定） | 每组仅 3 条样本 | 主动说局限（§4） |
| "追问是实时的" | 代码是静态预生成 | "预生成 2 轮追问，实时追问是优化点" |

## 10. 面试前必改（1 小时工作量）

1. **简历**：时间线 2026.03–06 → **2026.05–08**；删"O(1)"；QPS/P99 按 §8 改口径或删；"独立开发"→"基于开源项目二次开发的工程实践"。
2. **README**：删/改"源项目 TODO 中未实现的'打通模拟面试和知识库'"（源项目 04-12 已有 JD 匹配参考知识库）；差异表补一句"持久层 JPA→MyBatis-Plus 重写、Gradle→Maven"。
3. **prep 文档 Q1**："个人独立开发"→ 按 §1 话术。
4. **补一次真实压测**（wrk 打 /api/resumes/upload 或纯 XADD 接口），报告截图留档。
5. 打开 InterviewServiceImpl + prompts/*.st 过一遍出题 prompt（§7 内容核对）。

## 11. 复习计划（按剩余天数）

- **Day 1**：背 §1（来源话术）+ §6（语音链路）——最难的两块。
- **Day 2**：背 §3（Redis Stream）+ §4（RAG + RAGAS）+ §8（数字话术）。
- **Day 3**：背 §2 + §5 + §7；通读自己的 prep 文档（docs/interview-prep-talentpilot.md）；改简历/README。
- **Day 4+**：自问自答每个模块 2 遍（录音回听）；准备"最大坑"故事（§2 事务+XADD 竞态——已修复：afterCommit + PEL 重试，今早 3 份简历卡 PENDING 的真实案例、§6 回声、Nginx 502、Spring Data Redis 3.2 API 变更——prep 文档 Q20 有素材）。
