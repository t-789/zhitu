# LifeComposer 项目审核意见与改进建议

> 审核人：Claude（AI 辅助审核）  
> 审核日期：2026-11-03  
> 审核范围：`main` 分支 `caf73d3`，重点阅读了 `AgentOrchestrator`、`ChatController`、`ToolRegistry`、`RagSearchService`、`RecommendationService`、`WebSecurityConfig`、`ChatQuotaService`、`AsyncConfig`、`application.properties`、`pom.xml`、`chat_test.html`、`directions.json`、`prompts/chat_system.md` 以及 AGENTS/PLAN 系列文档。  
> 更新（2026-11-03）：P0-0 ~ P0-3 已在 v0.1.2 修复，见 `plans/Plan-v0.1.2.md`；最大输出统一为 2048，LLM 上下文窗口为最近 15 条。
> 说明：本报告以静态审阅为主，另跑了一次 `./mvnw test`（结果见 P0-0），没有修改任何代码。文中 `文件:行号` 以审核时的代码为准。

---

## 0. 总体评价

项目的**工程底座做得扎实**，在同类大创项目里少见：

- 安全面考虑周全：CSRF、Session 凭据版本、临时密码生命周期、最后管理员原子保护、管理控制台白名单投影、Bot 防护、异常矩阵。
- Agent 设计克制：白名单只读工具、画像变更"提议 → 确认卡片 → 独立确认接口 → 续答"两阶段、工具事件先推送再持久化、`prompt_version` 可追溯。
- 推荐是确定性、可解释的评分，LLM 只负责解释——这个方向是对的。
- 测试覆盖面广（契约、并发、隐私、分页）。

主要短板在于：**"智能"核心（画像 → 推荐 → 路径）的深度明显弱于基础设施的完成度**。现在的推荐本质上是"4 个计算机方向 × 标签集合交集"，与立项书"基于能力画像的**跨学科**成长路径规划"还有较大距离。另外，Agent 上下文管理有一个随使用时间必然暴露的问题（见 P0-1）。

优先级定义：

| 级别 | 含义 |
|---|---|
| **P0** | 真实使用一段时间后必然出问题，建议尽快修 |
| **P1** | 明显的功能/质量缺陷或安全加固项，下一个版本内处理 |
| **P2** | 工程整洁度、可维护性、性能储备 |

---

## 1. P0：建议尽快处理

### P0-0 测试基线当前不是全绿，其中一个测试会碰生产数据库（✅ v0.1.2 已修复）

审核时在 `LifeComposer/` 下执行 `./mvnw test`：**390 个测试，1 个失败、1 个错误**（文档记录的基线是 387 个全部通过）。

1. `AgentOrchestratorTest.capsRequestedMaxTokensToConfiguredLimit`：期望 1024，实际 2048。原因是提交 `34d4650 Modify max output token to 2k` 把 `AppSecurityProperties.java:40` 的默认值改成了 2048，而 `application.properties` 的 `app.security.chat-max-output-tokens=1024`、测试和 PROJECT_PLAN 里的"`max_tokens=1024`"都没有同步修改。现在 Java 默认值和配置文件各说各的。**请先确定到底要 1024 还是 2048**，再统一默认值、配置、测试和文档。
2. `LifeComposerApplicationTests.contextLoads`：这个类没有 `@ActiveProfiles("test")`，所以会用生产配置启动，在 `LifeComposer/data.db` 上执行 `DatabaseInitializer`（建表、迁移），然后因为没有设置 `LIFECOMPOSER_INITIAL_ADMIN_PASSWORD` 而失败。**这违反了"测试不得触碰生产 `data.db`"的约定**：本机原本没有 `data.db`，这次测试运行凭空建出了一个空库（已删除）。如果本地有真实数据，它会被执行迁移。修复方式：加上 `@ActiveProfiles("test")`；或者直接删掉这个类，因为其他集成测试已经覆盖了上下文启动。

### P0-1 聊天历史无上限地全部塞进 LLM 上下文（✅ v0.1.2 已修复）

- 位置：`AgentOrchestrator.java:128`、`:164`；`ChatMessageRepository.java:91`（`SELECT * FROM chat_messages WHERE user_id = ? ORDER BY ...`，无 `LIMIT`）。
- 问题：每一轮对话都会把该用户**全部历史**（包括每次工具调用的完整 JSON 结果，推荐/路径/RAG 结果往往很长）拼进请求。而且一轮对话内最多 8 次工具调用，每次都会重新发送整段上下文。
- 后果：
  1. 用户聊到几十轮后 token 成本线性甚至超线性增长；
  2. 最终超出模型上下文窗口，`/api/chat/*` 直接报错，而用户唯一的自救手段是"清空上下文"；
  3. 旧的工具结果（例如画像修改前的推荐评分）会和新数据互相矛盾，模型可能引用过期的数据回答，这违背了"只依据工具数据回答"的原则。
- 建议：
  - 只取最近 N 轮（例如 10~20 条 user/assistant 消息），并按 token 预算截断；
  - **历史中的 tool 结果只保留摘要或直接丢弃**，因为工具可以随时重新调用、得到最新数据——这正是 tool-use 架构的优势；
  - 截断必须保证 `assistant(tool_calls)` 与 `tool` 结果成对（现有 `flushToolExchanges` 已有配对逻辑，可复用）；
  - 进阶：超出窗口的旧对话由 LLM 生成"会话摘要"存一行，作为 system 之后的第一条上下文；
  - 补充测试：构造 200 条历史，断言发送给 LLM 的消息数量/字符数受控。

### P0-2 同一用户可并发发起多个流式会话，历史会交错（✅ v0.1.2 已修复）

- 位置：`ChatController.java:87` 之后的 `/stream` 处理；`AsyncConfig.java`。
- 问题：用户连点两次或开两个标签页时，两个 Agent 循环同时读写同一用户的 `chat_messages`。A 的 user 消息、B 的 user 消息、A 的 tool 结果、B 的回答会交错落库，下次重建上下文时顺序混乱（turnId 能救工具配对，但救不了"哪个回答对应哪个问题"）。画像候选确认的续答也可能与新消息并发。
- 建议：
  - 按 userId 加一个进程内锁（`ConcurrentHashMap<Integer, Semaphore>` 即可，与现有 `InMemoryMinuteRateLimiter` 一样是单机方案），第二个请求直接返回 `409 CHAT_IN_PROGRESS`；
  - 或者引入 `conversation_id`（见 §4 新功能"多会话"），每个请求只操作自己的会话。

### P0-3 SSE 线程池无上限（✅ v0.1.2 已修复）

- 位置：`AsyncConfig.java`——`Executors.newCachedThreadPool`。
- 问题：每个流式请求占用一个线程，最长持续 `sse-timeout-millis=300000`（5 分钟）。注册限速是"每 IP 每分钟 10 个"，聊天限速是"每用户每分钟 5 次"，两者叠加后一个人用脚本仍能在几分钟内开出几百个长连接线程。SQLite 写锁 + `busy_timeout=5000` 下，这会让所有人的写请求都变慢。
- 建议：改成有界 `ThreadPoolExecutor`（如 core 8 / max 32 / 队列 64），队列满时返回 `503 SERVER_BUSY`；配合 P0-2 的"每用户同时最多 1 个流"。

---

## 2. P1：功能与质量缺陷

### 2.1 推荐与画像（核心业务，最值得投入）

**P1-1 画像里一半的信息没有参与推荐。**  
`RecommendationService.recommend()`（`RecommendationService.java:60` 起）只用了 `skills`、`goals`、`availableTime`。`interests`、`experiences`、专业、年级完全没有进入评分。立项书说"从自身能力、**兴趣**和目标出发"，但兴趣对结果的影响是零；"大一零基础"和"大三有两段项目经历"的同学只要技能标签相同，得分就完全一样。

建议新增维度（权重同样走 `RecommendationProperties` 配置）：
- `interestFit`：兴趣与方向 `keywords` 的匹配；
- `experienceEvidence`：经历中出现的标签视为"有证据的能力"，权重高于单纯自填技能；
- `gradeFit`：方向可声明 `suitableGrades`（例如算法竞赛更适合大一大二起步，考研/保研方向更适合大三），按年级调整；
- `majorFit`：方向或资源的 `targetMajors` 与用户专业大类匹配。

**P1-2 能力是二值的，没有"等级"。**  
`matched = required.filter(userTags::contains)`——会一点 Python 和拿过蓝桥杯省一，在系统里都是"编程基础 ✅"。这导致 `difficultyFit` 和路径中的"当前水平"都很粗糙。`plans/Plan-v0.1-deferred.md` 第 2 条已识别该问题但尚未决策，建议尽快和数据同学定下 L1/L2/L3（入门/能独立完成/有竞赛或项目证据），`user_capability_states` 增加 `level` 与 `evidence_type` 列，评分改为 `min(userLevel / requiredLevel, 1)`。

**P1-3 目标相关性的匹配方式容易误判。**  
`goalRelevance()`（`:256`）用 `goal.contains(keyword)` 做子串匹配，并且命中任意一个关键词即给 1.0：
- `"编程"` 同时是 3 个方向的关键词，用户写"学好编程"，三个方向目标相关性都满分，区分度消失；
- 英文短关键词（如 `"AI"`）对英文目标有子串误命中风险（`"maintain"` 包含 `ai`）。

建议：关键词加权重（核心词 1.0、泛词 0.3），按命中的最高权重取值；或者利用现有的 Ollama embedding 计算"目标文本 ↔ 方向描述"的余弦相似度，与关键词分数取最大值。embedding 能力已经在项目里，复用成本很低。

**P1-4 评分维度之间存在重复计分。**  
`difficultyFit(direction.getDifficulty(), skillScore)`（`:196`）的结果依赖 `skillScore`，等于技能匹配被计算了两次（实际技能权重约为 0.40 + 0.10×0.6~0.7）。`preparationScore = 1 - (months - 2) / 12`（`:197`）中的常数 2 和 12 没有出处。权重本身是"实验默认值"可以理解，但公式应在 `scoreBreakdown` 或文档里写清楚，便于指导老师验收时理解。建议在 `AGENTS-architecture.md` 或单独的 `SCORING.md` 写出完整公式。

**P1-5 路径规划是模板文本，没有时间维度。**  
`task.setAction("补齐 " + tag + "：先完成对应课程，再用一个小项目或竞赛验证。")`（`:117`）对所有缺口输出同一句话；`expectedInvestment` 只是"每周 X 小时，持续 Y 个月"。用户真正需要的是：**第几周做什么、哪个比赛几月报名、和学期/考试周怎么错开**。
建议：
- 资源表补充 `registration_window` / `contest_date`（数据同学可从官网整理），路径按"当前日期 → 下一个可报名的比赛"倒排阶段；
- 输出结构化的 `milestones: [{weekRange, goal, resources, checkpoint}]`，前端可画成时间线；
- 根据 `availableTime` 和 `requiredHoursPerWeek` 的比值自动拉长或压缩周期，而不是只在文字里提醒"建议调整"。

**P1-6 方向库太小，且全部是计算机方向。**  
`directions.json` 只有 4 个方向（编程基础、算法竞赛、数据分析、AI 工具），这与"**跨学科**成长路径"的定位不符，也让推荐的排序意义有限。建议与数据同学优先扩充到 10~15 个，覆盖：科研/大创、创新创业（互联网+、挑战杯）、学科竞赛（电子设计、数学竞赛）、实习求职、保研/考研、交叉方向（金融科技、计算社会科学等），并让每个方向声明适用专业大类。

**P1-7 推荐结果没有沉淀。**  
`planning_history` 只有 QA 会写入，推荐/路径生成后不留痕。用户无法回看"上个月系统给我的建议"，也无法评估推荐随画像变化的效果。建议推荐和路径（尤其是用户点了"有用"的）写入 `planning_history`，附 `scoringVersion` 和画像 `version`，这也是后续做推荐效果评估的数据基础。

**P1-8 加分规则/加分记录没有和推荐联动。**  
项目已经导入了 346 条学院加分规则和用户的加分记录，但推荐评分完全不参考它们。对大学生来说，"这个比赛能加多少综测分"是很现实的决策因素。建议给资源/方向增加 `bonusValue` 维度，或在路径里标注"完成后预计可获得 X 分加分（依据规则 #xxx）"。

### 2.2 Agent 与 RAG

**P1-9 工具异常信息原样交给模型。**  
`ToolRegistry.java:70`：`ToolResult.error("TOOL_EXECUTION_ERROR", e.getMessage())`。`RuntimeException` 的 message 可能含 SQL、表名、文件路径，模型可能把它复述给用户，违反 `chat_system.md` 第 4 条"不要泄露内部实现"。同样的情况见 `SearchRagTool.java:80`（embedding 错误信息）。建议对模型只返回固定的中文描述 + 错误码，详细信息只进日志。

**P1-10 工具参数没有按 schema 校验。**  
工具声明了 JSON Schema（例如 `enum`、`additionalProperties: false`），但 `ToolRegistry.execute()` 只验证"是不是合法 JSON"。模型偶尔会传 `type: "比赛"` 之类不在枚举中的值，目前会静默地查出空结果，模型再告诉用户"没有找到"。建议在 `ToolRegistry` 做一层轻量校验（枚举、必填、类型），不合法时返回 `INVALID_ARGUMENTS` 并附上允许的取值，让模型能自我纠正。

**P1-11 RAG 每次检索都全表加载并反序列化所有向量。**  
`RagSearchService.java:56-59`：每次查询都 `findAllWithEmbedding()` 再逐条 `MAPPER.readValue(json, double[].class)`。28 条切片没问题，但数据同学扩充到几千条后，每次 RAG 调用都要解析几 MB JSON。建议：
- 启动时（以及导入完成后）把向量缓存在内存中（`List<float[]>`，预先归一化，余弦退化为点积）；
- 记录切片入库时的 `embedding_model`，只与同模型的向量比较——现在只校验维度，换一个同为 512 维的模型会得到无意义的相似度而不报错。

**P1-12 RAG 只有纯向量检索。**  
bge-small-zh 在专有名词（"蓝桥杯"、"CCSP"、"综测"）上的召回不稳定。建议加入关键词/BM25 召回后做融合（RRF），SQLite 自带 FTS5 可以直接用。路线图里已经提到 `样例/rag/test_questions.json`，建议把它做成自动化测试，输出 Recall@5 / MRR，作为调整 `minSimilarity`、切片策略时的客观依据。

**P1-13 额度在 LLM 不可用时也会被扣。**  
`ChatController.java:65`、`:87` 先 `tryConsume` 再调用 Agent，而 LLM 关闭/超时/返回错误时会抛 `LlmUnavailableException`，额度不退。服务端故障导致用户每日额度被消耗，体验不好。建议在 `LlmUnavailableException` 分支调用 `refund`（日计数 -1）。

**P1-14 LLM 调用没有重试。**  
`OpenAiCompatibleLlmClient` 只配置了超时（`:53-54`），网络抖动或 429/5xx 直接失败。建议对**还没输出任何 token 的阶段**做 1~2 次指数退避重试；流式已开始输出后则不重试，避免重复内容。

### 2.3 前端与安全加固

**P1-15 Markdown 渲染在 DOMPurify 加载失败时退化为不消毒。**  
`chat_test.html:488`：`if (typeof DOMPurify !== "undefined") { html = DOMPurify.sanitize(...) }`。CDN 被墙、超时或被广告拦截时，模型输出会**未经消毒**写入 `innerHTML`（`:794`、`:806`、`:839`）。模型输出会受 RAG 资料和资源描述影响，属于不可信内容。建议：
- **fail-closed**：DOMPurify 不可用时直接 `textContent` 显示纯文本；
- 第三方脚本加 `integrity`（SRI）和 `crossorigin`，或者把 marked/DOMPurify/KaTeX 下载到 `external/static/vendor/` 本地托管（国内网络环境下也更稳定）。

**P1-16 关闭了 X-Frame-Options，且没有 CSP。**  
`WebSecurityConfig.java:52` 把 frameOptions 整体 disable，页面可以被任意站点嵌入 iframe（点击劫持，尤其是管理控制台的封禁/授权按钮）。如果没有明确的嵌入需求，建议改为 `sameOrigin()`，并加一个基础 CSP（`default-src 'self'; script-src 'self' <cdn>; frame-ancestors 'self'`）。

**P1-17 默认 CORS 白名单包含 `https://*.trycloudflare.com`。**  
`application.properties:49`。任何人都能免费申请一个 trycloudflare 子域名，而 CORS 配置了 `allowCredentials=true`。目前 SameSite=Lax 能挡住大部分跨站带 Cookie 的请求，但这是在依赖第二道防线。注释里也写了"仅用于本地开发"，建议把它移到 `application-dev.properties` 或 `.env`，生产默认值中删除。

**P1-18 `/api/users/current` 返回的是 Session 中缓存的 User 对象。**  
`UserController` 的 `getCurrentUser` 直接读 `session.getAttribute("user")`，管理员修改用户信息、授权/撤权后，当前会话看到的仍是旧数据（`credential_version` 只在改密时递增）。建议按 id 重新查库，或在角色变更时也递增 `credential_version`。

### 2.4 正式用户端

**P1-19 普通用户没有可用的正式入口。**  
`home.html` 仍是英文占位页（"This page is for user management only."），用户能用的只有 `/front/profile` 和调试性质的 `/front/chat_test`。这是 v1 的既定目标，这里只强调：**对外演示/答辩前，这件事的优先级应高于新增后端功能**。建议的最小正式流程：注册 → 引导式画像填写（分步表单） → 推荐结果页（方向卡片 + 雷达图展示各维度得分） → 路径时间线 → 聊天追问。

---

## 3. P2：工程整洁度与可维护性

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| P2-1 | 引入了 JPA、Hibernate 方言、WebFlux，但代码中没有任何 `jakarta.persistence` 或 `WebClient` 用法；配置里还有 `ddl-auto=update`、`show-sql=true` | `pom.xml:49,66,106`；`application.properties:7-10` | 删除 `spring-boot-starter-data-jpa`、`hibernate-community-dialects`、`spring-boot-starter-webflux` 和全部 `spring.jpa.*` 配置。可以减少启动时间和依赖面，也避免新成员误以为可以用 `@Entity` |
| P2-2 | JSON 库同时用 Gson（16 个文件）和 Jackson | `ToolRegistry`、各 Tool、`OpenAiCompatibleLlmClient` | 统一到 Spring 默认的 Jackson，并注入共享的 `ObjectMapper` bean，而不是每个类各自 `new ObjectMapper()` |
| P2-3 | `pom.xml` 版本仍是 `0.0.1-SNAPSHOT`，实际已经是 v0.1.1 | `pom.xml:13` | 与发布版本同步，便于日志和构建产物溯源 |
| P2-4 | `spring-boot-devtools` 在主依赖中，生产配置有 `spring.devtools.add-properties` | `pom.xml:74`；`application.properties:19` | 确认 scope 为 `runtime` + `optional`，打包时排除 |
| P2-5 | 模板/静态资源路径用 `file:./external/...`，依赖启动时的工作目录 | `application.properties` | 从其他目录启动时会找不到页面。可改为可配置的绝对路径（环境变量），或打包进 classpath |
| P2-6 | 仓库没有 CI | 无 `.github/workflows` | 加一个 GitHub Actions：`./mvnw test` + 依赖漏洞扫描。5 人协作时这比本地自觉跑测试可靠得多 |
| P2-7 | `RecommendationService` 存在 N+1 查询：`resourcesTeaching()` 对每个缺口标签循环 `findByResourceId`，找不到时再 `findByType("course")` 全量扫描；`recommendationFor()` 为取一个方向重新计算全部方向 | `RecommendationService.java:290` 附近 | 一次请求内先把方向关联的资源批量加载成 Map；资源库变化不频繁，也可以做进程内缓存 |
| P2-8 | `AgentOrchestrator` 有两个构造函数，其中一个专门给测试/旧代码使用，生产构造函数还需要显式 `@Autowired` | `AgentOrchestrator.java:66-86` | 测试改为传入 `PromptCatalog`，删除旧构造函数，符合"构造函数注入"的规范 |
| P2-9 | 流式渲染每收到一个 token 就对完整文本重新 `marked.parse` + `DOMPurify.sanitize` | `chat_test.html:794` | 长回答时为 O(n²)，低端手机上会卡顿。可用 `requestAnimationFrame` 节流（每帧最多渲染一次） |
| P2-10 | 包名大小写混用：`Controller/Service/Repository/Entity/Exception/Security` 大写，`dto/config/agent/rag` 小写 | `src/main/java/...` | Java 规范是全小写。`plans/Plan-v0.1.1.md` 已提到改包名的计划，可以借那次重命名 PR 一起统一 |
| P2-11 | 限流和登录失败计数都在内存里 | `InMemoryMinuteRateLimiter`、`LoginAttemptService` | 单机部署可以接受，但重启会清零。如果将来多实例部署，需要迁到 SQLite 或 Redis。建议在文档里明确写"仅支持单实例" |
| P2-12 | `getRemoteAddr()` 作为限速 IP | `UserController.java:233` | 部署在 Nginx/Cloudflare 后面时，所有用户共享代理 IP，会被一起限速。需要配置 `server.forward-headers-strategy=native` 并只信任已知代理 |

---

## 4. 新功能建议

按"对项目目标的价值 / 实现成本"排序，前几条可以直接复用现有能力。

### 4.1 推荐效果评估闭环（★★★，成本低）
已经有 `recommendation_feedback` 表，但还没有用起来。建议：
- 管理控制台增加"推荐质量"视图：各方向的推荐次数、有用率、"太难/时间不合适"比例；
- 准备 20~30 个人工标注的"标准画像 → 期望方向"样例（`样例/data/student_profiles.json` 可作为起点），每次调整权重后跑一遍，输出命中率。这能把 `experimental-v1` 权重的人工验收变成可量化、可复现的过程，也是项目结题报告里很有说服力的一部分。

### 4.2 多会话（★★★，成本中）
现在每个用户只有一条无限增长的对话。增加 `conversations` 表（`id, user_id, title, created_at`），`chat_messages` 加 `conversation_id`：
- 同时解决 P0-1 的上下文膨胀和 P0-2 的并发交错；
- 用户可以按主题分开聊（"算法竞赛规划" / "保研准备"），体验接近主流 AI 产品；
- 标题可以在首轮回答后由 LLM 自动生成。

### 4.3 画像冷启动：对话式引导问卷（★★★，成本中）
新用户的画像是空的，推荐会全部落入 `INSUFFICIENT_INFO`。现有的 `propose_profile_update` + 确认卡片机制已经很完善，可以加一个"新手引导"Prompt：Agent 主动用 5~6 个问题了解专业、年级、技能、兴趣、可投入时间和目标，每答一题就生成候选，最后一次性确认。这比让学生填一张长表单的完成率高得多。

### 4.4 阶段性复盘与提醒（★★，成本中）
立项书中的"互勉与提醒"模块还没开始。建议的最小版本：
- 用户把某条路径设为"进行中"后，按 milestone 生成待办（`plan_tasks` 表），用户可以打勾；
- 每两周 Agent 发起一次复盘："上次计划完成了 2/5，是时间不够还是太难？"并据此更新画像或调整路径；
- 比赛报名截止前 N 天在首页提醒（依赖 P1-5 的 `registration_window` 数据）。

这能让"成长路径"从一次性的建议变成持续跟进的过程，也是和"问一次 ChatGPT"拉开差距的关键。

### 4.5 能力雷达图与成长轨迹（★★，成本低）
`user_capability_states` 已经有能力状态投影。前端可以用雷达图展示"我的能力 vs 目标方向要求"，并按画像 `version` 历史画出能力增长曲线。这类可视化成本低，在答辩/演示中效果很好。

### 4.6 组队匹配（★，成本高，后续再做）
"组队推荐"在立项书中列为后续增强。可以基于能力互补（A 缺的标签正好是 B 有的）+ 同一目标方向做匹配。它涉及用户之间的数据可见性，需要先设计隐私授权（用户主动开启"可被组队发现"，只暴露能力标签，不暴露经历细节），不建议在 v1 之前开始。

### 4.7 证据上传与能力认证（★，成本中）
证书、获奖证明（`credit_activities` 已有 `certificate_ref`）可以作为能力等级的证据来源（呼应 P1-2）：上传获奖证书 → 管理员/规则审核 → 对应能力从"自评"升级为"有证据"。这能让画像更可信，并且自然地和加分记录打通。

---

## 5. 建议的执行顺序

1. **本周**：P0-0（恢复测试全绿，隔离 `contextLoads`）、P0-1（上下文截断）、P0-2（单用户并发锁）、P0-3（有界线程池）、P1-15（DOMPurify fail-closed）、P1-9（工具错误脱敏）。改动都很小，风险低。
2. **v0.1.2**：P1-13 额度退还、P1-16/P1-17 安全头和 CORS、P2-1 清理无用依赖、P2-6 CI。
3. **v1 主线**：正式用户端（P1-19）+ 推荐深化（P1-1 兴趣/经历/年级、P1-2 能力等级、P1-5 时间线路径、P1-6 方向库扩充）+ 推荐评估闭环（4.1）。
4. **v1 之后**：多会话（4.2）、引导问卷（4.3）、复盘提醒（4.4）、RAG 混合检索与评测（P1-11/P1-12）。

其中第 3 步中，P1-2（能力等级）和 P1-6（方向库）依赖团队和数据同学的决策，建议尽早拉会把 `Plan-v0.1-deferred.md` 第 1、2、3 条定下来，否则后端会一直卡在"实验默认值"上。
