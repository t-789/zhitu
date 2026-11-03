# 架构、数据与安全

## 技术决策

| 决策 | 当前选择 | 约束/原因 |
|---|---|---|
| 数据库 | SQLite `data.db` | 轻量、零运维；连接串使用 `busy_timeout` 与 `transaction_mode=immediate` |
| 数据访问 | `JdbcTemplate` | 手动 SQL、RowMapper 和显式迁移；不使用 JPA Entity |
| 认证 | Session + Spring Security | 浏览器端使用 JSESSIONID；管理页面依赖 Thymeleaf |
| 密码 | BCrypt `PasswordEncoder` | 数据库只保存哈希，明文不能入日志、响应或报告 |
| 管理查询 | 专用只读 Repository + 白名单投影 | 不提供任意 SQL、任意表/列、全量导出或数据库下载 |
| LLM | OkHttp + Gson 的 OpenAI 兼容层 | `LlmClient` 支持真实客户端与 fallback；按 qa/planning/profile/sql/chat 路由 |
| Embedding | 独立 Ollama 客户端 | 不与生成式 LLM 共用配置或 API key 读取逻辑 |
| 构建 | Maven Wrapper | 在 `LifeComposer/` 下使用 `./mvnw` |

## Java 包职责

- `Controller`：REST API 和 `HomeController` 页面路由；端点权限由 Security 与 Controller 共同约束。
- `Service`：用户、反馈、画像、聊天额度、Prompt、推荐、管理员、LLM 等业务逻辑。
- `Repository`：直接写 SQL，使用 `RowMapper` 转换 Entity；用户表/画像表迁移通过 `PRAGMA table_info` 逐列补齐。
- `config`：`DatabaseInitializer` 建表与迁移；`LlmConfig`、`EmbeddingConfig`、`RagConfig`、`RecommendationProperties`、`AsyncConfig` 等配置。
- `Security`：CSRF、CORS、Session 凭据版本检查、临时密码限制、管理员内网限制。
- `Exception`：`ValidationExceptionHandler` 处理客户端输入，`GlobalExceptionHandler` 处理全局兜底，Bot 规则由 `BotRequestGuard` 共享。
- `agent`：`AgentOrchestrator` 最多 8 轮 tool-use，`ToolRegistry` 管理工具白名单，事件监听器支持 SSE 过程展示。
- `agent/tools`：画像提议、成长方向、能力差距、推荐理由、路径、资源、RAG、加分规则/记录、反馈等工具。
- `embedding` / `rag`：Ollama embedding、SQLite RAG 检索；默认 `topK=5`、`minSimilarity=0.2`。
- `importer`：独立 `DataImportCli` 与 `DataImportService`；导入模式不启动 Web，也不初始化管理员。
- `recommendation`：读取 `recommendation/directions.json`，使用配置权重进行确定性评分和阶段化路径生成。

## 数据库与迁移

- 建表统一由 `DatabaseInitializer` 的 `createXxxTableIfNeeded()` 完成；新增迁移必须幂等。
- `BOOLEAN` 以 SQLite `INTEGER` 保存；旧 SQLite 不支持 `DROP COLUMN`，不要依赖该语法。
- 存量 `goals` 表由 `DatabaseInitializer.migrateLegacyGoals()` 合并到 `user_profiles.goals` 后再按现有迁移策略处理。
- 成长数据核心表包括 `college_credit_rules`、`credit_activities`、`resources`、`rag_chunks`、`capability_tags`、`capability_reference`；v0.1 另有 `profile_change_candidates`、`user_capability_states`、`recommendation_feedback`。
- `chat_usage_daily` 记录每日额度；管理查询还覆盖聊天、画像、规划、资源、RAG、能力字典、反馈、审计等数据集。完整字段和索引必须以 [`SCHEMA.md`](./SCHEMA.md) 为准。

## 管理员初始化与凭据生命周期

- 固定 `admin/admin` 已移除。空库无管理员时必须设置 `LIFECOMPOSER_INITIAL_ADMIN_PASSWORD`，缺失、空白或弱密码直接 fail-fast；异常只提示变量名，不泄露值。
- 默认策略：管理员密码至少 12、最多 72 个字符，不含空白，不在弱密码表，不能是单字符重复；普通用户改密下限为 8。
- 已有管理员时不覆盖现有密码。发现历史 `admin/admin` BCrypt 哈希时，必须提供新密码并执行轮换。
- 管理员下发的临时密码有状态和有效期，首次登录强制改密；密码变更递增 `credential_version`，旧会话立即失效，修改密码的当前会话重新盖章。
- 临时密码会话只允许改密、当前用户、登出、CSRF 和改密页面；过期则销毁会话。
- 撤销或封禁最后一个可登录管理员由 `revokeAdminIfNotLast()` / `banIfNotLastLoginableAdmin()` 原子拒绝，失败映射为 `409 LAST_ADMIN_PROTECTED`。

## 异常与 HTTP 契约

- `ValidationExceptionHandler` 使用最高优先级处理 Bean Validation、`ConstraintViolationException`、类型不匹配、缺失参数、非法 JSON、媒体类型不支持，稳定返回 400。
- `GlobalExceptionHandler` 使用最低优先级处理未知路径、方法不支持、认证内部错误、客户端断开和最终 `Exception` 兜底；未知服务器异常写入系统反馈。
- `AdminController` 保留 `AdminApiException` / `DataAccessException` 的管理端稳定错误和审计。
- 校验失败、正常 404、Bot 拒绝、客户端主动断开不写系统反馈。
- `ClientAbortException` 返回 499；`AsyncRequestNotUsableException` 保持 500；空 User-Agent 或爬虫关键词由 `BotRequestGuard` 统一拒绝 403。
- API 请求返回 JSON；页面请求按错误码重定向到 `/error/{code}`。生产代码使用 `HttpStatus` 命名常量，不使用裸数字状态码。

## API 与安全边界

端点完整清单和响应契约见 [`API.md`](./API.md)，调用示例见 [`API_GUIDE.md`](./API_GUIDE.md)。前缀概览如下：

| 范围 | 前缀 | 权限概览 |
|---|---|---|
| 用户/反馈 | `/api/users/*`、`/api/feedback/*` | 注册、登录、公开反馈；用户管理和反馈管理需要 ADMIN |
| 画像/规划 | `/api/profiles/**`、`/api/planning/**` | 已登录，严格按当前用户所有权 |
| QA/Chat | `/api/qa/*`、`/api/chat/*` | health 公开；提问和聊天需要登录 |
| 成长数据 | `/api/college-credit-rules/**`、`/api/credit-activities/**`、`/api/resources/**`、`/api/rag-chunks/**`、`/api/capability-*/**` | 读取按 API 约定；写入和私有记录按角色/所有权限制 |
| 推荐 | `/api/growth-directions/**`、`/api/recommendation-feedback` | 登录后使用；反馈校验方向存在 |
| 管理 | `/api/admin/**`、`/admin/**` | ADMIN；查询分页 ≤200、排序/筛选白名单、每管理员限流和审计 |

安全契约：JSESSIONID Session；状态变更需要 `XSRF-TOKEN` Cookie + `X-XSRF-TOKEN` 头；登录重建 Session；Cookie 使用 HttpOnly/SameSite=Lax，HTTPS 时 Secure；CORS 默认限制到 localhost/127.0.0.1，可由 `app.security.allowed-origins` 显式配置。

管理控制台永不返回 `password_hash`、`embedding_json`、`certificate_ref`（只返回 `hasCertificate`）、列表中的 `preferences_json` 或 `stack_trace`；不支持任意 SQL、任意表列、CSV/JSON 全量导出或数据库下载。

## LLM、Prompt、Agent 与推荐

- 用途配置字段为 `provider`、`baseUrl`、`model`、`apiKeyEnv`、`enabled`、`timeoutMillis`、`temperature`；默认 DeepSeek `deepseek-flash`，`run.sh` 默认开启 qa/chat，其余关闭。
- `FallbackLlmClient` 只在用途关闭时提供 mock；`/api/chat/*` 显式拒绝 fallback，LLM 不可用时返回 503 或 SSE error。
- `OpenAiCompatibleLlmClient` 支持 tools、tool_calls 和 SSE；不要在代码、日志中输出真实 API key。
- Prompt 文件位于 `src/main/resources/prompts/`，含 `chat_system`、`profile_extraction`、`direction_explanation`、`path_suggestion`；`PromptCatalog`/`PromptComposer` 负责加载和版本记录。
- Agent 通过白名单工具读取画像、能力、方向、路径、资源、RAG、加分数据并提交推荐反馈；最多 8 轮。工具过程通过 thinking、tool_call、tool_result、token、assistant_message、error、done 等 SSE 事件展示。
- v0.1.2：`chat_messages` 保留全部历史，发给 LLM 的上下文只取最近 `app.security.chat-context-messages`（默认 15）条用户消息/AI 最终回答（`ChatMessageRepository.findRecentContext`，工具行随窗口发送但不计数）；同一用户同时只允许一个 AI 请求（`ChatConcurrencyGuard`，409 `CHAT_IN_PROGRESS`）；流式线程池有界（`chat-stream-max-concurrent`，满时 503 `CHAT_SERVER_BUSY` 并退还额度）。
- 画像变更必须先生成 `profile_change_candidates`，客户端确认后才写入画像；确认过程支持过期、并发、冲突和幂等处理。
- 能力状态是当前画像投影：删技能会删对应标签，但经历对象保留证据；推荐评分输出中文维度，不把内部 `goalRelevance` / `scoreBreakdown` 字段名直接展示给用户。

## 测试基础设施

- 测试基类是 `BaseControllerTest`，组合 MockMvc、Spring Security 测试和独立数据库。
- 测试库为 `target/test-data.db`；测试应验证生产 `data.db` 的时间戳未变化。
- `application-test.properties` 绑定测试管理员密码、Thymeleaf 外部模板目录和 `server.port=0`；测试规则不应降低生产安全策略。
- `AuditLogCapture` 可挂载 Log4j2 appender 断言审计事件和“日志不含敏感值”。
