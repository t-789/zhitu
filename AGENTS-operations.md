# 运行、配置与验证

## 工作目录

除根目录文档操作外，命令默认在 `LifeComposer/` 执行：

```bash
cd /Users/liuzy/Desktop/大学生成长规划Agent/LifeComposer
```

应用默认端口为 `18000`；测试使用随机端口。生产数据库是 `data.db`，测试数据库是 `target/test-data.db`。

## 常用命令

```bash
# 编译
./mvnw compile

# 全量测试
./mvnw test -Dspring.profiles.active=test

# SQLite 原生库解包遇到临时目录权限问题时
mkdir -p target/tmp
./mvnw test -Dspring.profiles.active=test -Djava.io.tmpdir=target/tmp

# 独立数据导入：执行完退出，不启动 Web，不初始化管理员
./import.sh --import-dir=../样例 --report=target/import-report.json
./import.sh --import-dir=../样例 --dry-run
./import.sh --import-dir=../样例 --rag-chunks --rebuild-embeddings

# 本地启动（脚本会加载 .env）
./run.sh

# 清理构建产物
./mvnw clean
```

导入 CLI 的参数、报告格式和数据文件要求以 `DataImportCli`、`DataImportService` 与 `样例/SAMPLES-README.md` 为准。不要用导入 CLI 验证 Web 登录，也不要让导入任务初始化管理员。

## LLM 与环境变量

先复制 `.env.example` 为 `.env`，再由用户手动填写密钥。不要读取、回显或提交真实 key。

可用 provider：`deepseek`、`qwen`（百炼）、`glm`、`gpt`（Longxia API）、`ollama`（本地 qwen 3.5:9b）。每个用途有一组同名变量：

```text
LLM_QA_PROVIDER / BASE_URL / MODEL / API_KEY_ENV / ENABLED
LLM_PLANNING_PROVIDER / BASE_URL / MODEL / API_KEY_ENV / ENABLED
LLM_PROFILE_PROVIDER / BASE_URL / MODEL / API_KEY_ENV / ENABLED
LLM_SQL_PROVIDER / BASE_URL / MODEL / API_KEY_ENV / ENABLED
LLM_CHAT_PROVIDER / BASE_URL / MODEL / API_KEY_ENV / ENABLED
```

默认 provider/base URL/model 为 `deepseek`、`https://api.deepseek.com/v1`、`deepseek-flash`；qa/chat 默认开启，planning/profile/sql 默认关闭。`API_KEY_ENV` 只保存环境变量名，例如 `DEEPSEEK_API_KEY`，不是 key 本身。

首次在空数据库启动，还需要用户设置 `LIFECOMPOSER_INITIAL_ADMIN_PASSWORD`：至少 12 个字符，不得是弱密码或含空白。`run.sh` 缺失时只提示变量名，不打印密码。

## 最小 API 验证流程

完整流程和 JSON 示例见 [`API_GUIDE.md`](./API_GUIDE.md)。基础探活：

```bash
curl -s http://localhost:18000/api/qa/health
curl -s http://localhost:18000/api/csrf
```

浏览器/脚本调用状态变更 API 时必须保留 Cookie，并发送从 `/api/csrf` 获取的 `X-XSRF-TOKEN`。登录成功会重建 Session，建议重新获取 CSRF token。`/api/chat/stream` 是 SSE，客户端应处理 thinking、tool_call、tool_result、token、assistant_message、profile_change_proposal、awaiting_confirmation、error、done 等事件；`external/static/sse-client.js` 是已验证的解析实现。

公开端点包括 `/api/csrf`、注册/登录、公开反馈、`/api/qa/health`；其余用户业务通常需要登录，`/api/admin/**` 与 `/admin/**` 需要 ADMIN。临时密码会话只允许改密、当前用户、登出、CSRF 和改密页面。

## 配置来源

- 生产默认值：`src/main/resources/application.properties`。
- 测试覆盖：`src/test/resources/application-test.properties`。
- 页面目录：`external/templates/`；静态资源目录：`external/static/`。
- LLM 配置由 `run.sh` 将环境变量转换为 Spring system properties；不要直接把密钥写入 `application.properties`。
- 推荐实验权重和阈值位于 `lifecomposer.recommendation.*`；方向目录位于 `src/main/resources/recommendation/directions.json`。
- RAG 配置位于 `lifecomposer.rag.*`；embedding 客户端是 Ollama 独立配置。

## 变更验证清单

修改后至少根据影响范围执行：

- Java/Repository/Service：`./mvnw test -Dspring.profiles.active=test`。
- RAG 检索评测（需本地 Ollama 与 `样例/rag/`，默认跳过）：`cd LifeComposer && ./mvnw test -Dtest=RagEvalTest -Drag.eval=true [-Drag.eval.dir=../样例/rag]`，输出纯向量 / 纯关键词 / 混合三种模式的 Recall@5、Hit@5、MRR 与逐题命中，用于调整 `minSimilarity`、切片策略、`hybridCandidateK` 时的客观依据。
- Controller/Security/Session/CSRF：补充或运行对应 MockMvc、契约和权限测试。
- 数据库迁移：验证空库、已有库、重复启动和 `data.db` 未被测试修改；更新 [`SCHEMA.md`](./SCHEMA.md)。
- API 响应或错误码：更新 [`API.md`](./API.md) 与 [`API_GUIDE.md`](./API_GUIDE.md)。
- Prompt/Agent/RAG/推荐：更新夹具或回归测试，确认 SSE、工具数据和待复核数据行为。
- 页面：确认动态内容仍使用安全 DOM API；正式聊天页应复用 `sse-client.js` 和已验证的确认卡片逻辑。

## 发布说明维护

只有用户明确要求时才更新 `LifeComposer/external/templates/release_notes.html`；新版本 `<div class="container">` 插入顶部，参考 `release_notes_example.html`。版本历史和功能清单放在 [`AGENTS-status.md`](./AGENTS-status.md) 与发布说明中，不要把每次改动重新塞回入口文件。
