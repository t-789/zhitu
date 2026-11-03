# 当前状态、里程碑与历史

## 当前完成范围

基础后端、成长数据底座、Agent tool-use、RAG、SSE、推荐和运维控制台已经搭建。自动化测试基线为 402 个通过（v0.1.2）；具体当前数量以最近一次测试结果为准。

已具备的核心业务：

- 用户注册、登录、Session、管理员角色、封禁和反馈管理。
- 用户画像 `/api/profiles/me`，包含 `availableTime`、skills、interests、experiences、goals；规划历史 `/api/planning/history`。
- QA `/api/qa/health`、`/api/qa/ask`；QA 可配置 fallback。
- Chat `/api/chat/send`、`/api/chat/stream`、`/api/chat/history`、`/api/chat/context`；Chat 不使用 fallback。
- 成长数据：加分规则、加分记录、资源、RAG 切片、能力标签、能力映射字典。
- 管理控制台：`/api/admin/**` 只读查询与明确管理动作；`/admin/**` 页面共用管理布局。
- 独立数据导入 CLI、Ollama embedding、SQLite RAG 检索和 Agent 多轮工具调用。

## v0.1 业务闭环

- M1 正式画像：`/front/profile` 填写/查看/编辑；PUT 使用 `version` 乐观锁，非法 JSON/重复值稳定返回 400。
- M2 聊天辅助画像：`profile_change_candidates`、`propose_profile_update`、`profile_change_proposal`/`awaiting_confirmation`；确认后才写画像，拒绝理由不进入画像。
- M3 Prompt 目录：`src/main/resources/prompts/*.md` 带版本头，`PromptCatalog`/`PromptComposer` 参与画像提取、方向解释和路径建议。
- M4 能力归一：别名、`skill_mapping`、`tags_to_merge`、`skill_profiles`、能力差集和 `user_capability_states`。
- M5 方向与路径：`recommendation/directions.json`、可配置权重/阈值、确定性评分明细、三档回归夹具和阶段化路径。
- M6 工具与反馈：成长方向、能力差距、推荐理由、路径计划、推荐反馈工具及 `/api/recommendation-feedback`。
- M7 `chat_test` 调试工作台：对话、画像、候选、能力、推荐、工具和 RAG 监控；确认卡片位于输入框上方；SSE 解析抽到 `external/static/sse-client.js`。

## 最近审核整改

- 画像确认续答计入聊天额度；重复确认不触发 LLM。
- 候选确认先原子抢占 `PENDING → CONFIRMED`，同时校验过期时间，再在同一事务写画像；并发拒绝、过期和冲突不留下未确认写入。
- `chat_messages.prompt_version` 与 SSE `prompt_versions` 记录实际 Prompt 版本。
- 能力状态是当前画像投影：删技能会删标签，经历对象保留证据。
- 调试面板补齐 RAG `data.items`、评分明细、资源来源、`needs_review`、工具耗时和错误详情。
- M4 预检改走 `DataImportService` 并记录真实未匹配项；推荐反馈校验方向存在。
- 实际调用推荐工具后自动补方向解释/路径建议 Prompt；Agent 只能依据工具数据回答，待复核数据不推测具体字段。
- 多候选可依次确认：集合字段合并、标量快照未变可应用、已生效重复候选幂等；全部确认处理完后才按批次统一续答。
- 异常处理按输入错误/全局兜底拆分；客户端断开不写系统反馈；Bot 不能通过输入错误路径绕过 403。

## 下一阶段目标

- v1：把 `chat_test` 验证过的 SSE 客户端、画像面板、确认卡片、工具监控和推荐监控迁移到正式聊天页。
- 从注册开始覆盖画像 → 推荐 → 路径 → 资源 → 反馈，不再依赖 `testuser_N`。
- 使用 `样例/rag/test_questions.json` 做 RAG 检索回归，继续扩充资源库/方向库；评分权重经人工验收后替换实验默认值。

## 历史版本

| 版本 | 日期 | 主要内容 |
|---|---|---|
| v0.0.1 | 2026-06-27 | 初始后端基础：用户、反馈、画像、目标、历史、问答、LLM 抽象 |
| v0.0.2 | 2026-06-28 | AI 对话原型、`chat_messages`、`/api/chat`、Log4j2 和缺陷修复 |
| v0.0.3 | 2026-09-05 | goals 合并进画像；新增成长数据底座 6 表及基础 API |
| v0.0.4 | 2026-09-13 | 独立导入 CLI、Ollama embedding、SQLite RAG、Agent tool-use、SSE、deepseek-flash；164 测试全绿 |
| v0.0.5 | 2026-09-13 | CSRF、Session 加固、聊天分钟/每日限流、`chat_usage_daily`、注册/登录限速和审计；188 测试全绿 |
| v0.0.6 | 2026-09-14 | 管理员初始化/密码安全、临时密码和最后管理员保护；管理查询 API 与 13 个控制台视图；311 测试全绿 |
| v0.0.7 | 2026-09-20 | HTTP 状态码常量、异常归属、Bot 防护、客户端断开处理、导入 DTO/错误契约和异常矩阵；336 测试全绿 |
| v0.1 | 2026-09-23 | 正式画像、候选确认、Prompt 目录、能力归一、推荐/路径、反馈工具、`chat_test` 调试工作台及审核整改 |
| v0.1.2 | 2026-11-03 | P0 整改：`max_tokens` 统一 2048、`contextLoads` 隔离测试库、LLM 只发最近 15 条用户/回答消息（`chat-context-messages`）、单用户单请求 409 `CHAT_IN_PROGRESS`、有界流式线程池 503 `CHAT_SERVER_BUSY`；402 测试全绿 |

版本详细发布说明见 [`LifeComposer/external/templates/release_notes.html`](./LifeComposer/external/templates/release_notes.html)。
