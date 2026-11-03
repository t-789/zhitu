# API 端点清单

> v0.0.7 当前已实现端点（2026-09-20 同步：人工审核问题整改统一状态码写法与异常处理归属；对普通客户端端点与响应体格式未变；两处显式变化：客户端断开 `ClientAbortException` 由 400 改为 499，Bot/空 UA 请求在任意异常路径（含非法 JSON、缺少参数、类型转换等输入错误）统一 403、不再降级为 400，详见「错误响应约定」）。与各 Controller 及 WebSecurityConfig 保持一致。Milestone 5 起所有状态变更请求需要 CSRF token；Milestone 7 起 `/api/admin/**` 与 `/admin/**` 全部仅限 `ROLE_ADMIN`。

> **v0.0.7 校对修正（逐端点对照源码，2026-09-22）**：
> ① **未登录访问受保护端点返回 `403`，不是 `401`** —— 这些路径在 Spring Security 过滤器链上就被拦截，而本项目未配置自定义 `AuthenticationEntryPoint`，走默认的 `Http403ForbiddenEntryPoint`；`401` 仅来自会话守卫的 `SESSION_EXPIRED` / `PASSWORD_CHANGE_REQUIRED` / `TEMP_PASSWORD_EXPIRED` 三种情况（见「临时密码生命周期」），以及极少数过滤器链已放行但会话缺 `user` 属性的边界分支。
> ② **CSRF 覆盖所有 POST/PUT/DELETE，包括 `register` / `login` 等公开端点** —— 「公开端点」不等于「免 CSRF」，缺 token 一律 403。
> ③ 字段级请求/响应说明、完整错误码与坑位清单见 [`API_GUIDE.md`](./API_GUIDE.md)。

---

## 用户管理

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/users/register` | 无（**需 CSRF**） | 请求 `username`、`password`，可选 `email`（合法邮箱、≤100 字符）和 `realName`（≤50 字符）；新用户页用学号作为 `username` 且要求邮箱/姓名。旧客户端可不传；邮箱小写存储、大小写不敏感唯一，未做邮箱验证。成功/失败均返回**纯文本**（`注册成功` / `注册失败，用户名或邮箱可能已存在`）。每 IP 每分钟 10 次，超限 429 `REGISTER_RATE_LIMIT` |
| POST | `/api/users/login` | 无（**需 CSRF**） | 请求中的 `username` 可为用户名/学号或已注册邮箱（3-100 字符）；登录并设置 session。返回 JSON：`message` / `username` / `type` / `passwordChangeRequired` / `tempPasswordExpiresAt`。密码错误返回 400 `BAD_CREDENTIALS`；账号被封禁返回 400 `LOGIN_REJECTED`；连续失败 5 次锁定 15 分钟并返回 429 `LOGIN_LOCKED`；临时密码过期返回 403 `TEMP_PASSWORD_EXPIRED`；`passwordChangeRequired=true` 时必须先访问 `/front/change-password` |
| POST | `/api/users/logout` | 已登录 | 登出，清除 session。返回纯文本 `登出成功` |
| GET | `/api/users/current` | 已登录 | 获取当前用户信息（id/username/type/avatar/**isBanned**/banEndTime）。未登录由过滤器链拦截 → **403** |
| GET | `/api/users/all` | ADMIN | 获取所有用户（不含密码哈希）。该端点字段名是 `banned`（与 `/current` 的 `isBanned` 不同） |
| PUT | `/api/users/{userId}/grant-admin` | ADMIN | 授予管理员。用户不存在返回 400（注意：不是 404） |
| PUT | `/api/users/{userId}/revoke-admin` | ADMIN | 撤销管理员；系统必须保留至少一个可登录管理员，否则返回 409 `LAST_ADMIN_PROTECTED`；用户不存在返回 404 |
| PUT | `/api/users/{userId}/ban` | ADMIN | 封禁（body `{"banTime":"1d"}`）。单位：`y`=365 天、**`m`=30 天（月，不是分钟）**、`d`=天、`h`=小时，可组合如 `1y30d`；`"0"` 为永久。body 缺失/非法返回 400 `INVALID_INPUT`，最后一个可登录管理员返回 409 `LAST_ADMIN_PROTECTED`。封禁到期后下次登录自动解封 |
| PUT | `/api/users/{userId}/unban` | ADMIN | 解封 |
| POST | `/api/users/admin/reset-password/{userId}` | ADMIN | v0.0.6：下发**临时密码**，body `{"newPassword":"..."}`。管理员账户要求 ≥12 字符、普通用户账户要求 ≥8 字符，且均不得是已知弱密码；响应只返回 `message`/`passwordChangeRequired`/`tempPasswordExpiresAt`，绝不回显密码。临时密码带状态与有效期，见下文「临时密码生命周期」 |
| POST | `/api/users/password` | 已登录 | v0.0.6：自助修改密码，body `{"currentPassword":"...","newPassword":"..."}`；错误当前密码/弱新密码/与原密码相同返回 400 `PASSWORD_REJECTED`。成功后当前会话保留，该账户的其他会话立即失效 |
| GET | `/front/change-password` | 已登录 | v0.0.6：修改密码页面（临时密码会话唯一可访问的业务页面） |
| POST | `/api/users/admin/chat-quota/reset/{userId}` | ADMIN | v0.0.5：重置指定用户当前 Asia/Shanghai 自然日的聊天额度（无日期参数），返回 `userId`/`usageDate`/`usedToday`/`remainingToday`/`dailyLimit`/`minuteLimit`/`minuteRemaining`，并写审计日志 |
| GET | `/api/csrf` | 无 | v0.0.5：返回当前会话的 CSRF token（`headerName`=`X-XSRF-TOKEN`、`parameterName`=`_csrf`、`token`），并写入可读的 XSRF-TOKEN Cookie |

## 反馈管理

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/feedback/submit` | 无（**需 CSRF**） | 用户反馈（可匿名，已登录则记录用户；未登录记为 `Anonymous`/`userId=-1`）。`content` 空白返回 400 |
| POST | `/api/feedback/system-error` | 无（**需 CSRF**） | 系统错误报告（`content` 空白时落库为 `未提供错误详情`） |
| GET | `/api/feedback/all` | ADMIN | 所有反馈 |
| GET | `/api/feedback/type/{type}` | ADMIN | 按类型过滤（`user` / `system`） |
| POST | `/api/feedback/{id}/resolve` | ADMIN | 标记已解决/未解决（body `resolved` 缺省视为 true），记录 `resolvedBy` |

## 用户画像

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/profiles/me` | 已登录 | 获取当前用户画像；尚未填写时返回 404 纯文本 `用户档案不存在` |
| PUT | `/api/profiles/me` | 已登录 | Upsert 当前用户画像，**全量覆盖**：未提交的字段会被写成 `NULL`（局部更新前必须先 GET 合并）；返回落库后重新读取的画像。v0.1 起响应含 `version`（乐观锁版本）；请求带 `version` 时执行 `WHERE user_id=? AND version=?`，冲突返回 409 `PROFILE_VERSION_CONFLICT`，不带则保持旧覆盖语义并递增版本 |
| GET | `/api/profiles/me/capabilities` | 已登录 | v0.1 M4：当前用户归一后的标准能力标签列表（tag / level / evidence / source / confidence / updatedAt）。能力状态是当前画像的投影：清空或删除技能会同步删除对应标签，对象型经历会转成可读证据而不是丢失 |

- 画像字段：`college`、`major`、`grade`、`studentId`、`skillsJson`、`interestsJson`、`experiencesJson`、`preferencesJson`
- **v0.0.3 新增字段**：`availableTime`（每周可投入时间，自由文本）、`goals`（用户成长目标，JSON 数组字符串）
- 所有 `*Json` 字段与 `goals` 在传输层都是 **string**（内容是 JSON 文本），前端收发需自行 `JSON.stringify` / `JSON.parse`
- **v0.1 M4 预检**：`CapabilityPreflightService` 在真实 `DataImportService` 导入（与 `import.sh` 同一套 section 拍平逻辑）后读取 `样例/data/student_profiles.json`，输出覆盖率、逐项未匹配技能、资源模板展开和未匹配模板标签到 `target/capability-preflight-report.json`
- 原独立 `goals` 表及其整套目标 CRUD 端点已移除，目标统一由 `user_profiles.goals` 列承载
- **v0.1 M1 校验**：数组字段必须是合法 JSON 数组且元素非空、不重复；`preferencesJson` 必须是 JSON 对象；非法输入返回 400 + 稳定错误码（`INVALID_PROFILE_JSON` / `DUPLICATE_FIELD_VALUE` / `INVALID_FIELD_VALUE`）。正式填写页为 `/front/profile`

## 规划历史

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/planning/history` | 已登录 | 当前用户的 AI 交互历史 |
| GET | `/api/planning/history/{id}` | 已登录 | 历史详情（仅本人）；不存在或非本人均返回 400 纯文本（`规划记录不存在` / `无权访问此记录`，注意不是 403） |

## 问答

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/qa/health` | 无 | LLM 健康检查（返回 provider/model/enabled/status 状态） |
| POST | `/api/qa/ask` | 已登录 | 提问，返回回答和 historyId。**保留 mock fallback**：LLM 失败时返回 `mocked:true`、`provider:"mock"`、答案前缀 `[Mock]`，并写一条 `status=FAILED` 历史（与 `/api/chat/*` 不同） |

## 对话 (Chat)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/chat/send` | 已登录 | 兼容旧客户端的一次性 JSON；body `{"message":"...","maxTokens":可选}`（`message` ≤5000 字符；服务端强制 `maxTokens` 上限 2048）。内部复用同一 Agent 多轮 tool-use 逻辑。LLM 不可用时返回 HTTP 503 `{"error":"LLM_UNAVAILABLE",...}`；同一用户已有 AI 请求在处理时返回 HTTP 409 `{"error":"CHAT_IN_PROGRESS",...}`（不扣额度）；超过分钟/日额度返回 HTTP 429，body 含 `retryAfterSeconds` / `remainingToday` / `minuteRemaining` 等 |
| POST | `/api/chat/stream` | 已登录 | SSE 流式对话。事件：`prompt_versions` / `thinking_start` / `thinking_tick`(可选) / `thinking_end` / `tool_call` / `tool_result` / `token` / `assistant_message` / `profile_change_proposal` / `awaiting_confirmation` / `error` / `done`；准入失败返回 JSON 而不是 SSE：额度不足 429；同一用户已有 AI 请求在处理 409 `CHAT_IN_PROGRESS`（不扣额度）；全局流式并发已满 503 `CHAT_SERVER_BUSY`（额度退还）。提议画像变更时本轮以 `awaiting_confirmation` 结束，不等待用户点击 |
| GET | `/api/chat/history` | 已登录 | 获取当前用户的**全部**对话历史（`chat_messages` 按时间稳定排序）；包含 user / assistant / tool 行。不受 LLM 上下文窗口限制 |
| DELETE | `/api/chat/context` | 已登录 | 清除当前用户的对话上下文（**物理删除**，会影响后续 LLM 上下文），返回 `{"deleted": n}` |

> **v0.0.4 说明**：`/api/chat/*` 不使用 fallback 或 mock 回复。API key 缺失、HTTP 非 2xx、超时、格式错误、流中断均视为 `LLM_UNAVAILABLE`；SSE 发送 `error` 事件后关闭。一次工具调用会持久化两条 `chat_messages`：assistant 的 tool_call JSON 和 tool 的 tool_result JSON。工具调用超过 8 轮时以 `MAX_TOOL_ROUNDS` 失败。
>
> - 思考计时：首次收到 `reasoning_content` 才发送 `thinking_start`；reasoning 中出现 `</think>` / `done thinking`，或 reasoning 阶段结束（首个 `content`、流结束、报错）时发送 `thinking_end`。普通 `token` / `tool_call` 不再提前停止计时。
> - 持久化顺序：SSE 场景下先成功发送 `tool_call` / `tool_result` / `assistant_message` 事件，再写入数据库；`tool_call` 与对应 `tool_result` 成对原子写入，浏览器断开时不会留下未送达或不成对的 tool/assistant 行。非流式 `/api/chat/send` 直接写入。
> - 多工具与轮次边界：同一轮返回多个工具调用时，写入数据库仍按每次调用两条记录，并在 tool_call/tool_result JSON 中携带 `turnId`；拼装下一轮 LLM 上下文时按 `turnId` 分组，同一轮合并为一条带多个 `tool_calls` 的 assistant 消息 + 多条 tool 结果，不同轮次保持独立，符合 OpenAI/DeepSeek 协议。旧数据无 `turnId` 时回退为连续记录分组。
> - thinking 计时状态、未完成 assistant 文本不落库；连接中断时保留已完整写入的 user 消息和已成功送达的工具调用记录。
> - `role=assistant` 的历史行若 `content` 形如 `{"type":"tool_call",...}`，是工具调用载荷而非自然语言回答，前端应按 `type` 字段分流渲染。
>
> **v0.0.5 配额说明**：
> - 每个已认证用户每分钟最多 5 次聊天，每天（Asia/Shanghai）最多 100 次；`/api/chat/send` 与 `/api/chat/stream` 每次请求只计一次。
> - 被 429 拒绝的请求不扣减日额度；LLM 失败、SSE 中断和客户端断开仍保留已准入请求的用量，避免通过失败绕过限额。
> - 服务端强制 `max_tokens=2048`（v0.1.2 起，原为 1024）；客户端传入更大的 `maxTokens` 会被截断。
> - 管理员可用 `POST /api/users/admin/chat-quota/reset/{userId}` 重置当前自然日额度，并写入审计日志。
>
> **v0.1.2 上下文与并发说明**：
> - 数据库保留全部历史；每次调用 LLM 只发送最近 `app.security.chat-context-messages`（默认 15）条**用户消息与 AI 最终回答**，窗口内这些轮次的 tool_call/tool_result 一并发送但不计数。窗口总是从一条用户消息开始；当前这条用户消息计入窗口。画像确认后的续答使用同一窗口。
> - 同一用户同时只允许一个 AI 请求（`/api/chat/send`、`/api/chat/stream`、画像确认续答共用）；先占位再扣额度，被拒绝的并发请求不扣额度。画像确认时如遇占用，决定照常写入，仅跳过续答（`agentAnswered=false`）。
> - 流式对话全局最多 `app.security.chat-stream-max-concurrent`（默认 32）个同时运行，无排队；已满时返回 503 `CHAT_SERVER_BUSY` 并退还额度。以上均为单实例内存实现。

## 画像变更确认（v0.1 M2）

聊天只能创建 `PENDING_CONFIRMATION` 候选，用户确认后才写入正式画像；拒绝理由不会被当作画像事实。

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/profiles/change-candidates` | 已登录 | 当前用户待确认候选列表；返回 candidateId / field / oldValue / newValue / rationale / status / expiresAt |
| GET | `/api/profiles/change-candidates/{candidateId}` | 已登录 | 单个候选（仅本人）；不存在或非本人返回 404 `CANDIDATE_NOT_FOUND` |
| POST | `/api/profiles/change-candidates/{candidateId}/decision` | 已登录 | body `{"decision":"CONFIRM"|"REJECT","reason":可选}`。确认后在 `WHERE user_id=? AND version=?` 条件下合并写入并递增版本；返回 status / mergedVersion / decidedAt / agentMessage。过期返回 200 + `EXPIRED`，快照版本冲突返回 200 + `CONFLICT`，重复提交幂等返回原状态 |

- 聊天可提议字段由 `lifecomposer.profile-change.allowed-fields` 控制，默认仅 `availableTime,skillsJson,interestsJson,experiencesJson,goals`；学号、学院、专业、年级必须由正式表单填写。
- **确认原子性**：确认时先执行 `UPDATE ... SET status='CONFIRMED', merged_version=NULL WHERE candidate_id=? AND status='PENDING_CONFIRMATION' AND expires_at > ?` 抢占候选，成功后才在同一事务内写 `user_profiles`。有效期条件与抢占在同一条 SQL 中，覆盖 `expireStale()` 与抢占之间的过期窗口。
- **多候选依次确认**：同一轮提议可以生成多个候选。确认其中一个后，其余候选不会因为 `base_version` 变旧而直接失败：集合字段（技能/兴趣/经历/目标）按并集合并；标量字段（可投入时间）在快照值仍未被改动时也可应用。只有同一字段已被其他请求改成不同值时才返回 `CONFLICT`；值已生效的重复候选幂等返回 `CONFIRMED` 且不再写画像。
- **批量确认后统一续答**：每次决策后若用户仍有 `PENDING_CONFIRMATION` 候选，响应返回 `continuationDeferred:true`，本轮不调用 LLM、不消耗聊天额度；最后一条处理完才用整个批次（`batch_id`）的确认/拒绝结果作为一次上下文发起续答。并发拒绝、过期或版本冲突导致抢占失败时，正式画像完全不会被修改；抢占后任何异常都会回滚候选状态。
- 候选有效期由 `lifecomposer.profile-change.ttl-minutes`（默认 30）控制；过期候选不会写入画像。
- 决策后服务端发起一次短的 Agent 续答（`agentMessage`），把结构化结果交回模型；LLM 不可用时返回确定性兜底文案，决策本身仍已落库。
- **AI 额度边界（审核整改）**：只有真正产生新决策（PENDING → CONFIRMED/REJECTED）的那一次请求才调用 Agent 续答，并经过 `ChatQuotaService` 与 `/api/chat/*` 相同的分钟/日额度。重复确认、已过期、冲突和跨用户请求是幂等业务操作，不再调用 LLM、不再消耗额度。额度耗尽时决策仍成功落库，响应含 `quotaExceeded:true` 与 `retryAfterSeconds`，仅跳过 AI 续答。
- **可追溯性**：响应含 `promptVersions`（本次使用的提示词 id → 版本），SSE 在流开始发送 `prompt_versions` 事件；assistant 消息把版本指纹写入 `chat_messages.prompt_version`。当本轮实际调用了推荐/能力/路径工具时，服务端会在后续轮次的 system prompt 中补上 `DIRECTION_EXPLANATION` 与 `PATH_SUGGESTION` 契约，不依赖用户原话是否命中关键词。

## 成长方向与路径推荐（v0.1 M5/M6）

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/growth-directions` | 已登录 | 首批成长方向目录（id/name/description/targetTags/entryTags/requiredHoursPerWeek/difficulty/preparationMonths/resourceIds） |
| GET | `/api/growth-directions/recommendations` | 已登录 | 当前用户方向推荐；返回 score、classification（SUITABLE / PARTIALLY_SUITABLE / NOT_RECOMMENDED / INSUFFICIENT_INFO）、matchedTags、missingTags、timeNote、scoreBreakdown、scoringVersion、followUpQuestions |
| GET | `/api/growth-directions/{directionId}/gap` | 已登录 | 用户标准能力标签与方向目标标签的差集；方向不存在返回 404 `DIRECTION_NOT_FOUND` |
| GET | `/api/growth-directions/{directionId}/path` | 已登录 | 阶段化路径：currentLevel / gapTasks / practiceTasks / resources（含 dataQuality）/ expectedInvestment |

- 评分权重、阈值和版本由 `lifecomposer.recommendation.*` 配置；方向与资源 id 来自 `src/main/resources/recommendation/directions.json`，均为可迁移数据，不硬编码在评分方法中。
- 评分是确定性的；LLM 只解释 DTO，不重新发明分数或标签。
- 面向用户时，Agent 禁止输出 `scoreBreakdown`、`goalRelevance`、`skillMatch`、`matchedTags` 等内部字段名；推荐工具额外返回 `explanationHints`（技能匹配/时间匹配/目标相关性/难度/准备周期），Agent 必须用中文维度解释，缺少信息时先追问。
- Agent 必须忠实于工具数据：只根据工具返回的资源、日期、链接和评分依据回答，不得用自身知识补全或推断；`needs_review` 数据只说明“需要人工复核”，不推测具体哪些字段未确认。
- M6 Agent 工具：`list_growth_directions` / `get_capability_gap` / `get_recommendation_reasons` / `get_path_plan` / `submit_recommendation_feedback`。

## 推荐反馈（v0.1 M6）

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/recommendation-feedback` | 已登录 | body `{"directionId":"...","feedbackType":"useful|irrelevant|too_hard|time_mismatch|goal_changed","note":可选}`；非法类型返回 400 `INVALID_FEEDBACK`；方向不存在返回 404 `DIRECTION_NOT_FOUND`，不写入无快照的反馈 |
| GET | `/api/recommendation-feedback/me` | 已登录 | 当前用户的反馈记录（按时间倒序） |

- 反馈记录方向 id、类型、备注、评分配置版本和推荐快照字段；v0.1 不做在线学习。

## 加分规则 (College Credit Rules)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/college-credit-rules` | 已登录 | 规则列表；可选过滤参数 `college`、`creditType`(graduation/recommendation)、`category` |
| GET | `/api/college-credit-rules/{id}` | 已登录 | 规则详情；不存在返回 400 纯文本 `加分规则不存在` |
| POST | `/api/college-credit-rules` | ADMIN | 新建规则（校验 college/creditType/category/credits） |

## 加分记录 (Credit Activities)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/credit-activities` | 已登录 | 当前用户的加分记录列表 |
| GET | `/api/credit-activities/{id}` | 已登录 | 加分记录详情（仅本人）；不存在/非本人返回 400 纯文本 |
| POST | `/api/credit-activities` | 已登录 | 新建加分记录（归属当前用户；`verified` 强制为 0，`ruleId` 若提供必须存在） |
| PUT | `/api/credit-activities/{id}` | 已登录 | 更新加分记录（仅本人；`verified` 由服务端控制，用户不可改） |
| DELETE | `/api/credit-activities/{id}` | 已登录 | 删除加分记录（仅本人），返回 `{"message":"加分记录已删除"}` |

## 成长资源库 (Resources)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/resources` | 已登录 | 资源列表；可选 `type` 过滤（`competition` 或 `course`），不带参数返回全部；`type` 非法返回 400 纯文本 |
| GET | `/api/resources/{id}` | 已登录 | 资源详情：优先按业务键 `resource_id`（如 `competition_001`）匹配，纯数字回退物理主键；不存在返回 404 纯文本 |

## RAG 检索切片 (RAG Chunks)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/rag-chunks` | 已登录 | 切片列表；可选 `relatedResourceId` 过滤（关联 `resources.resource_id` 业务键），不带参数返回全部 |

> ⚠️ **数据暴露面**：该端点返回 `RagChunk` 实体全字段，**包含完整向量 `embeddingJson`**，且对任意已登录用户开放。管理控制台出于同一考虑刻意只返回 `embeddingJsonLength` 而不返回向量，说明该字段被团队视为不应外泄的运维数据 —— 建议后端在此端点剔除 `embeddingJson`，或前端不使用该字段。详见 [`API_GUIDE.md`](./API_GUIDE.md) 第 9.2 节。

## 能力标签字典 (Capability Tags)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/capability-tags` | 已登录 | 全部标准能力标签（按标签名排序） |

## 能力映射字典 (Capability Reference)

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/capability-reference` | 已登录 | 字典行列表；`section` **可选**（不是必填），不带参数返回全部；一旦提供则仅支持：`tags_to_merge`、`skill_mapping`、`skill_profiles`、`role_profiles`、`major_categories`、`_meta`，否则 400 纯文本 |

## 管理控制台 (Admin Console, v0.0.6)

> 全部为只读 `GET`，仅 `ROLE_ADMIN` 可访问；不提供任意 SQL、任意表名/列名、数据库文件下载或批量导出。
> 统一分页信封：`{"items":[...],"page":1,"pageSize":50,"total":123,"totalPages":3}`；默认每页 50，最大 200。
> 统一错误：`{"error":"<CODE>","message":"..."}`，错误码包括 `INVALID_PARAMETER`、`UNKNOWN_PARAMETER`、`NOT_FOUND`、`ADMIN_RATE_LIMIT`、`ADMIN_QUERY_FAILED`、`FORBIDDEN`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/dashboard` | 总览：12 张表记录数、用户/管理员/封禁/画像统计、今日聊天用量与 TOP 用户、未解决反馈、LLM 失败数、RAG embedding 状态分布 |
| GET | `/api/admin/users` | 用户与额度列表：状态、角色、创建时间、是否有画像、当日已用/剩余额度；筛选 `q`/`type`(1,2)/`banned`(0,1)/`hasProfile`(0,1) |
| GET | `/api/admin/users/{id}/profile` | 单个用户画像详情（含受控展示的 `preferencesJson`、完整学号）；不存在返回 404 |
| GET | `/api/admin/user-profiles` | 画像列表：学院/专业/年级/技能/兴趣/经历/目标/可用时间；`studentId` 默认掩码，`preferencesJson` 不入列表；筛选 `userId`/`q`/`college`/`major`/`grade` |
| GET | `/api/admin/planning-history` | `planning_history` 列表（仅摘要 + 长度）；筛选 `userId`/`type`/`status`/`provider`/`model`/`dateFrom`/`dateTo` |
| GET | `/api/admin/planning-history/{id}` | 单条详情，请求/响应 JSON 截断后受控展示 |
| GET | `/api/admin/chat-messages` | `chat_messages` 列表（摘要 + `structured` 标记）；筛选 `userId`/`role`(user,assistant,tool)/`dateFrom`/`dateTo` |
| GET | `/api/admin/chat-messages/{id}` | 单条完整内容（tool call/result 结构）；超长截断并返回 `contentTruncated` |
| GET | `/api/admin/feedback` | 反馈与系统错误列表；`user_agent` 截断、不返回 `stack_trace`；筛选 `type`(user,system)/`resolved`(0,1)/`userId`/日期 |
| GET | `/api/admin/feedback/{id}` | 单条详情：URL、UA、受控截断的堆栈（不超过 `console-diagnostic-length`） |
| GET | `/api/admin/college-credit-rules` | 加分规则全字段分页；筛选 `college`/`creditType`/`category`/`compLevel`/`awardTier`/`q` |
| GET | `/api/admin/college-credit-rules/{id}` | 规则详情（含完整备注） |
| GET | `/api/admin/credit-activities` | 加分记录；`certificate_ref` 不返回，只给 `hasCertificate`；筛选 `userId`/`creditType`/`verified` |
| GET | `/api/admin/credit-activities/{id}` | 记录详情 |
| GET | `/api/admin/resources` | 资源库；筛选 `type`/`difficulty`/`dataQuality`/`provider`/`q` |
| GET | `/api/admin/resources/{id}` | 资源详情（JSON 字段安全格式化展示） |
| GET | `/api/admin/rag-chunks` | RAG 切片；不返回 `embedding_json`，只给 `embeddingJsonLength`、模型、维度、状态、hash、错误摘要；筛选 `embeddingStatus`/`sourceType`/`relatedResourceId`/`q` |
| GET | `/api/admin/rag-chunks/{chunkId}` | 切片详情（正文截断后展示） |
| GET | `/api/admin/capability-tags` | 能力标签字典；筛选 `category`/`q` |
| GET | `/api/admin/capability-tags/{name}` | 标签详情（别名/证据 JSON 安全格式化） |
| GET | `/api/admin/capability-reference` | 能力引用字典；筛选 `section`/`q` |
| GET | `/api/admin/capability-reference/{id}` | 字典条目详情 |
| GET | `/api/admin/chat-usage` | `chat_usage_daily` 每日计数；筛选 `userId`/`usageDate`/`dateFrom`/`dateTo` |

**通用参数**：`page`（≥1，上限 100000）、`pageSize`（1-200）、`sort`（每个数据集独立白名单）、`dir`（`asc`/`desc`）；未在数据集白名单内的参数一律 400 `UNKNOWN_PARAMETER`。
**限流**：每个管理员每分钟最多 `lifecomposer.admin.console-queries-per-minute`（默认 240）次查询，超限 429 `ADMIN_RATE_LIMIT`；查询使用 `lifecomposer.admin.console-query-timeout-seconds`（默认 10s）JDBC statement timeout。
**审计**：成功查询记录 `AUDIT event=admin_query`（admin/dataset/filters/total/returned/elapsedMs），被拒查询记录 `AUDIT event=admin_query_rejected`（admin/uri/reason）；两者都不记录被查看的正文内容。

## 管理员初始化与密码策略（v0.0.6）

- 首次启动（数据库中没有任何管理员）必须通过环境变量 `LIFECOMPOSER_INITIAL_ADMIN_PASSWORD` 提供初始管理员密码；缺失、空白或不满足强度要求时应用 fail-fast，启动失败信息只包含变量名。
- 管理员密码强度：≥ `lifecomposer.admin.min-password-length`（默认 12）字符、≤ 72 字符、不含空白字符，且不能是 `admin`/`000000`/`testuser` 等已知弱密码或同一字符重复。
- 数据库中已存在管理员时忽略该变量，且绝不覆盖现有密码；检测到历史遗留的 `admin/admin` 弱密码账户时必须提供合格新密码才能启动，启动后立即轮换并记录 `AUDIT event=admin_password_rotated`。
- 独立导入 CLI（`lifecomposer.import.mode=true`）跳过管理员初始化，不要求该变量。
- 哈希只以 BCrypt 形式存储；密码值不出现在日志、异常、响应或报告中。

### 临时密码生命周期（v0.0.6 审查整改）

管理员下发的密码是**真正的临时密码**，不再等同于一次普通重置：

| 语义 | 实现 |
|------|------|
| 状态 | `users.password_reset_required = 1`；登录响应返回 `passwordChangeRequired: true` |
| 有效期 | `users.temp_password_expires_at`，由 `lifecomposer.admin.temp-password-ttl-minutes`（默认 1440 分钟）决定；过期后登录返回 403 `TEMP_PASSWORD_EXPIRED`，强制修改期间过期同样会立即登出 |
| 首次登录强制修改 | 带该状态的服务端会话只能访问 `POST /api/users/password`、`GET /api/users/current`、`POST /api/users/logout`、`GET /api/csrf` 与 `/front/change-password`；其他请求返回 401 `PASSWORD_CHANGE_REQUIRED`（页面重定向到 `/front/change-password`） |
| 一次性 | 修改成功后 `password_reset_required = 0`、`temp_password_expires_at = NULL`，旧临时密码立即失效 |
| 会话失效 | 任何密码变更都会 `credential_version = credential_version + 1`；会话登录时记录该版本号，后续请求版本不匹配即被服务端销毁并返回 401 `SESSION_EXPIRED`（发起修改的会话会被重新盖章，因此本人不被登出，其他设备全部失效） |

> 实现：`Security/SessionCredential` + `Security/SessionCredentialGuardFilter`（挂在 Spring Security 过滤器链的 `AuthorizationFilter` 之前）+ `UserService.setTemporaryPassword/changeOwnPassword`。
>
> 密码本身仍只以 BCrypt 哈希存储；`temp_password_expires_at` 的值绝不会回显。
>
> 注意：上表的 `401` 与「未登录访问受保护端点的 `403`」是两回事 —— 前者由会话守卫在**已认证会话**上主动返回并带 `{"error":"..."}` 体，后者由过滤器链在**无认证**时返回。

### 并发安全的管理员保护（v0.0.6 审查整改）

「系统必须保留至少一个可登录管理员」不再依赖“先查数量再更新”：

- `UserRepository.revokeAdminIfNotLast()` / `banIfNotLastLoginableAdmin()` 把数量条件写进 `UPDATE ... WHERE` 子句，且只作用于单行；
- 两个操作都在 `@Transactional` 中执行，连接串使用 `transaction_mode=immediate`，事务开始即取得 SQLite 写锁，因此并发调用被串行化，不可能同时通过检查；
- 回归测试 `AdminLastAdminConcurrencyTest` 用栅栏同时释放多个线程，验证只有 `n-1` 次能成功、最终至少保留 1 个可登录管理员。

## 管理控制台信任模型（v0.0.6 审查确认）

管理员被定义为**完全可信的调试/运维角色**（单实例部署）：

- **绝对不返回**：`password_hash`、原始 `embedding_json` 向量、`certificate_ref` 证书路径、服务端环境变量与会话/Cookie；
- **可以看到**：用户画像中的个人信息。画像列表为了减少肩窥风险把学号做掩码显示，而 `GET /api/admin/users/{id}/profile` 详情会返回**完整学号**与 `preferencesJson`，因为排查用户规划结果时需要这些字段。

同一表述同时写在 `AdminController` 的类注释与 `external/static/admin.js` 的文件头，修改信任模型时必须三处同步。

## 页面路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 首页 |
| GET | `/front/login` | 知途用户登录页（公开） |
| GET | `/front/register` | 知途用户注册页（公开；共用滑动面板模板） |
| GET | `/adminlogin` | 管理员登录页（公开；登录后仍由 `ROLE_ADMIN` 保护 `/admin/**`） |
| GET | `/login` | 旧入口，跳转至 `/front/login` |
| GET | `/admin` | 运维控制台 · 总览 (ADMIN) |
| GET | `/admin/user` | 运维控制台 · 用户与额度 (ADMIN) |
| GET | `/admin/profile` | 运维控制台 · 用户画像 (ADMIN) |
| GET | `/admin/planning` | 运维控制台 · AI 规划记录 (ADMIN) |
| GET | `/admin/chat` | 运维控制台 · AI 对话记录 (ADMIN) |
| GET | `/admin/feedback_management` | 运维控制台 · 反馈与错误 (ADMIN) |
| GET | `/admin/credit-rules` | 运维控制台 · 加分规则 (ADMIN) |
| GET | `/admin/credit-activities` | 运维控制台 · 加分记录 (ADMIN) |
| GET | `/admin/resources` | 运维控制台 · 资源库 (ADMIN) |
| GET | `/admin/rag` | 运维控制台 · RAG 切片 (ADMIN) |
| GET | `/admin/capability-tags` | 运维控制台 · 能力标签 (ADMIN) |
| GET | `/admin/capability-reference` | 运维控制台 · 能力字典 (ADMIN) |
| GET | `/admin/usage` | 运维控制台 · 聊天用量 (ADMIN) |
| GET | `/release-notes` | 发布说明 |
| GET | `/front/chat_test` | AI 对话调试工作台（v0.1 M7：左侧对话 + 右侧画像/能力/候选/工具/RAG/推荐监控，确认卡片位于输入框上方） |
| GET | `/front/profile` | 我的成长画像（v0.1 M1：创建/查看/编辑正式画像） |
| GET | `/front/change-password` | 修改密码页（v0.0.6，临时密码会话唯一可访问的业务页面） |

> v0.0.6：`/admin` 与 `/admin/**` 在 Spring Security 过滤链上即要求 `ROLE_ADMIN`，页面路由内还有一层 `isAdmin` 校验；全部页面共用 `external/static/admin.css` + `external/static/admin.js`（同一套导航、分页器、筛选栏与详情抽屉），页面模板只声明 `data-admin-view`。

---

## CSRF 防护（v0.0.5）

- 服务端使用 `CookieCsrfTokenRepository`，通过可读的 `XSRF-TOKEN` Cookie + `GET /api/csrf` 暴露 token；请求头名为 `X-XSRF-TOKEN`（`XsrfHeaderAliasFilter` 会把它映射到 Spring Security 默认的 `X-CSRF-TOKEN`）。
- 前端统一加载 `/csrf.js`，包装 `window.fetch`，对 `POST` / `PUT` / `DELETE` 自动发送 `X-XSRF-TOKEN` 头。
- `GET` / `HEAD` / `OPTIONS` 不需要 CSRF token；其余状态变更请求缺少或携带不匹配 token 时返回 403。
- **该规则与「是否为公开端点」无关**：`POST /api/users/register`、`POST /api/users/login`、`POST /api/feedback/submit`、`POST /api/feedback/system-error` 虽然是公开端点，仍然必须携带有效 token。
- Session Cookie 设置 `HttpOnly=true`、`SameSite=Lax`；HTTPS 部署时设置 `SESSION_COOKIE_SECURE=true` / `CSRF_COOKIE_SECURE=true`。`XSRF-TOKEN` 需要被同源 JS 读取，因此不设为 HttpOnly。
- CORS 允许来源通过 `app.security.allowed-origins` 显式配置，禁止使用 `*` 搭配 credentials；默认值为 `http://localhost:*`、`http://127.0.0.1:*`、`https://localhost:*`、`https://*.trycloudflare.com`（最后一项为调试用的 Cloudflare 临时隧道通配子域）。
- 登录成功后重建 Session，防止 Session Fixation。

## 错误响应约定（v0.0.7 归属整理）

> v0.0.7 整理异常的**处理归属**，除下列显式变化外不改变既有状态码或响应体格式；生产代码的状态码统一使用 `HttpStatus` 命名常量。

- **未认证（无有效会话）**：受保护端点在 Spring Security 过滤器链即被拦截，因未配置自定义 `AuthenticationEntryPoint`，返回 **403**（Spring Boot 默认错误体 `{timestamp,status,error,path}`，`server.error.include-message=never` 故无 message）。这是**最常见**的"未登录"表现，前端拦截器不要只判 401。
- **客户端输入错误**（`ValidationExceptionHandler`，`@Order(HIGHEST_PRECEDENCE)`）：Bean Validation → `400` + 字段到消息的 JSON map；`ConstraintViolationException` → `400 "Invalid input"`；类型转换失败 → `400 "Method argument type mismatch"`；缺少必填参数 → `400 "Missing servlet request parameter."`；非法/缺失 JSON body → `400 "请求体缺失或格式不正确"`；不支持的媒体类型 → `400 "Unsupported media type"`。这些请求**不**写入系统错误反馈。
- **Bot 请求**：空 User-Agent 或爬虫关键词 → `403 "Forbidden"`。该策略由两个 advice 共用的 `BotRequestGuard` 执行，覆盖全部异常处理器（输入错误、路由错误、客户端断开、全局兜底），因此即使请求先触发非法 JSON、缺少参数或类型转换等输入错误，也仍然是 403 而不会降级为 400。**联调时请始终携带 `User-Agent`。**
- **路由与服务端错误**（`GlobalExceptionHandler`，`@Order(LOWEST_PRECEDENCE)`）：未知路径对 `/api/**` 返回 404（无 body），页面请求 302 重定向到 `/error/{code}`；不支持的 HTTP method 对 API 维持既有 404 契约；未知服务器异常返回 `500 "Internal Server Error"` 并写入系统 feedback（反馈写入失败不覆盖原错误）。
- **客户端断开**：`ClientAbortException` → `499 Client Closed Request`，响应体 `"ClientAbortException"`；`AsyncRequestNotUsableException` → 维持 `500 "Internal Server Error"`。两者都被视为流生命周期事件（debug 日志、不写 feedback），且不再产生空 200 的“假成功”。`499` 是事实标准状态码，客户端已断开时不可见，但代理与访问日志可据此区分断开与成功/业务错误。
- **管理端**：`/api/admin/**` 继续由 `AdminController` 局部处理，保持 `{"error":"<CODE>","message":"..."}` 与审计行为（`INVALID_PARAMETER` / `UNKNOWN_PARAMETER` / `NOT_FOUND` / `ADMIN_RATE_LIMIT` / `ADMIN_QUERY_FAILED`）。
- **响应体形态**：返回 `Map`/DTO/`List` 的端点是 JSON；返回 `String` 的端点（如 `注册成功`、`登出成功`、`权限不足`、`用户档案不存在`）是**纯文本**，不要直接 `JSON.parse`。

## 认证与权限说明

- **公开端点**：`/api/users/register`, `/api/users/login`, `/api/csrf`, `/api/feedback/submit`, `/api/feedback/system-error`, `/api/qa/health`（**其中 4 个 POST 仍需 CSRF token**）
- **需要 ADMIN 角色**：`/api/admin/**`（v0.0.6 管理控制台）, `/admin` 与 `/admin/**`（v0.0.6 管理页面）, `/api/users/all`, `/api/users/*/grant-admin`, `/api/users/*/revoke-admin`, `/api/users/*/ban`, `/api/users/*/unban`, `/api/users/admin/**`, `/api/feedback/all`, `/api/feedback/type/**`, `/api/feedback/*/resolve`, `POST /api/college-credit-rules`
- **v0.0.6 变更**：`POST /api/users/admin/reset-password/{userId}` 下发带状态与有效期的临时密码且不回显，用户首次登录必须通过 `POST /api/users/password` 修改；撤销或封禁最后一个可登录管理员返回 409，保证系统始终存在可登录管理员
- **v0.0.6 会话失效**：密码变更会递增 `users.credential_version`，服务端会立即销毁用旧密码建立的会话（401 `SESSION_EXPIRED`）；带临时密码状态的会话只能访问修改密码相关端点（401 `PASSWORD_CHANGE_REQUIRED`）
- **需要认证**：`/api/users/password`（v0.0.6 自助修改密码）, `/api/users/current`, `/api/users/logout`, `/api/profiles/**`, `/api/planning/**`, `/api/qa/ask`, `/api/chat/**`, `/api/college-credit-rules/**`（除 POST 管理外）, `/api/credit-activities/**`, `/api/resources/**`, `/api/rag-chunks/**`, `/api/capability-tags/**`, `/api/capability-reference/**`, `/front/**`
- **未认证的状态码**：以上受保护端点在没有有效会话时返回 **403**（Spring Security 过滤器链默认行为），**不是 401**；401 只由会话守卫产生（见上）
- **v0.0.3 变更**：原目标管理端点相关认证匹配已随 goals 表一并移除；新增 6 个 `/api` 前缀的认证配置（college-credit-rules / credit-activities / resources / rag-chunks / capability-tags / capability-reference）
- **认证机制**：JSESSIONID Cookie 传递认证状态
- **CORS 限制**：默认本地开发环境（`http://localhost:*`, `http://127.0.0.1:*`, `https://localhost:*`）并额外允许调试用的 Cloudflare 临时隧道（`https://*.trycloudflare.com`），可通过 `app.security.allowed-origins` 显式覆盖
- **限流与额度**：注册（每 IP/分钟）、登录（连续失败临时锁定）、聊天（每用户 5/分钟 + 100/天），详见对应章节

---

> 更详细的字段级说明（请求参数表、成功响应样例、全部错误码、Agent 工具入参出参）见 [`API_GUIDE.md`](./API_GUIDE.md)。
