# LifeComposer / 大学生成长规划 Agent

> 本文件是项目工作指引。附录按架构、安全、运维和项目状态拆分，按任务需要阅读。

## 项目定位

LifeComposer 是大学生成长规划助手后端，当前应用位于 `LifeComposer/`。技术栈为 Java 25、Spring Boot 4.0.5、SQLite、`JdbcTemplate`、Session + Spring Security、Thymeleaf。系统包含用户/反馈/画像/规划历史、问答、AI 对话、成长数据、RAG、Agent tool-use、推荐、管理控制台和独立数据导入 CLI。

当前状态和版本背景见 [`AGENTS-status.md`](./AGENTS-status.md)。实现细节和安全契约见 [`AGENTS-architecture.md`](./AGENTS-architecture.md)。命令、配置和测试入口见 [`AGENTS-operations.md`](./AGENTS-operations.md)。

## 新上下文阅读顺序

1. 先看本文件的“目录地图”和“不可违反的规则”。
2. 改 Java、数据库、安全、Agent 或推荐时读 [`AGENTS-architecture.md`](./AGENTS-architecture.md)。
3. 运行服务、导入数据、调 API、配置 LLM 或跑测试时读 [`AGENTS-operations.md`](./AGENTS-operations.md)。
4. 判断功能是否已实现、了解里程碑和下一步时读 [`AGENTS-status.md`](./AGENTS-status.md)。
5. 修改具体接口或表结构前，继续阅读 [`API.md`](./API.md)、[`API_GUIDE.md`](./API_GUIDE.md) 或 [`SCHEMA.md`](./SCHEMA.md)。

## 目录地图

```text
仓库根目录/
├── AGENTS.md                         # 项目工作指令入口（CLAUDE.md 仅一行 `@AGENTS.md`）
├── AGENTS-architecture.md            # 架构、数据库、安全、异常、Agent
├── AGENTS-operations.md              # 运行、配置、测试和验证
├── AGENTS-status.md                  # 当前状态、里程碑和历史版本
├── AGENTS-20250925.md                # 2025-09-25 归档版本，不作为工作指令
├── API.md                            # API 端点、权限、CSRF、错误响应
├── API_GUIDE.md                      # API 调用示例、限额、错误码和运维查询
├── SCHEMA.md                         # 数据库表、字段、索引、迁移和 SQLite 说明
├── 对接回复-v0.1.3.md                # 给登录端（王钰博）的对接结论与任务清单
├── PROJECT_PLAN.md                   # 项目目标和阶段规划
├── CONTRIBUTING.md                   # 分支、PR、文件边界和协作规则
├── plans/                            # 项目计划文件；当前集成计划见 Plan-v0.1.1.md
├── .omo/                             # 本地工作流状态；不作为业务源码
├── 样例/                             # 本地数据交付包，供导入 CLI 使用
└── LifeComposer/                     # Spring Boot 应用根目录
    ├── pom.xml                       # Maven 构建与依赖
    ├── mvnw / mvnw.cmd               # Maven Wrapper
    ├── run.sh                        # 启动脚本，加载 .env 并注入 LLM 配置
    ├── import.sh                     # 独立导入 CLI，不启动 Web
    ├── stop_run.sh                   # 本地停止脚本
    ├── .env.example                  # 环境变量模板，不含真实密钥
    ├── data.db                       # 本地 SQLite 数据库，禁止提交
    ├── src/main/java/org/example/lifecomposer/
    │   ├── Controller/               # REST 与页面路由
    │   ├── Service/                  # 业务服务、LLM、画像、推荐、管理员服务
    │   ├── Repository/               # JdbcTemplate SQL、RowMapper、迁移
    │   ├── Entity/                   # 普通 POJO，不是 JPA Entity
    │   ├── dto/                      # 请求/响应 DTO 和 LLM DTO
    │   ├── config/                   # 数据库、LLM、RAG、推荐、安全相关配置
    │   ├── Security/                 # Spring Security、Session、CSRF、管理员内网保护
    │   ├── Exception/                # 输入校验、全局异常和页面错误处理
    │   ├── agent/                    # AgentOrchestrator、ToolRegistry、工具上下文
    │   │   └── tools/                # 白名单 Agent 工具
    │   ├── embedding/                # Ollama embedding 客户端
    │   ├── rag/                      # RAG 检索与命中结果
    │   ├── importer/                 # DataImportCli、DataImportService、报告
    │   ├── recommendation/           # 方向目录和确定性推荐评分
    │   └── util/                     # 通用辅助代码
    ├── src/main/resources/
    │   ├── application.properties   # 生产默认配置
    │   ├── log4j2-spring.xml         # Log4j2 配置
    │   ├── prompts/*.md              # 带版本头的 Prompt 目录
    │   └── recommendation/
    │       └── directions.json       # 成长方向目录
    ├── src/test/java/                # 单元、Repository、Controller、契约测试
    ├── src/test/resources/
    │   └── application-test.properties # target/test-data.db 隔离配置
    └── external/
        ├── templates/                # Thymeleaf 页面、管理控制台、错误页
        └── static/                   # csrf.js、sse-client.js、admin.js/css 等
```

仓库根目录还有立项/调研资料和本地原始数据；它们不是运行时源码。`样例/`、`raw/` 等资料目录按当前 `.gitignore` 策略处理，导入流程见运维附录。

当前开发者：刘正扬（`liuzybj@bupt.edu.cn`），AI 辅助编程主导，Java/Spring 边用边学。

## 不可违反的规则

- 永不把 API key、密码、Cookie、Session、真实用户信息写入代码、配置、测试、日志、截图、PR 或文档。
- 不读取或泄露 `/Users/liuzy/.config/opencode/opencode.jsonc`、`/Users/liuzy/.zshrc` 中的密钥值；需要 LLM 密钥时只说明环境变量名和填写位置。
- API key 只从环境变量注入；`run.sh` 可以透传和显示 provider/model/enabled，但不得打印 key 值。
- 永不提交 `LifeComposer/data.db`、`.env` 或其他运行时密钥/数据文件。
- 不复制出第二套用户表、认证后端或 SQLite 数据库；沿用 Session + CSRF。
- 访问 profile、credit activities、planning、chat 等私有数据时，必须从 `SecurityContext` 解析当前用户并检查所有权，禁止跨用户读取。
- 动态页面内容使用安全 DOM API，优先 `textContent`；不能把模型输出直接交给 `innerHTML`。

## 关键工作方式

- 代码行为以现有实现、自动化测试和 API/Schema 文档为准；修改接口、表、认证或推荐行为时同步更新测试和契约。
- 构造函数注入；Repository 使用 `JdbcTemplate`/手写 SQL，不使用 JPA `@Entity`；表初始化和迁移必须幂等。
- DTO/Entity 沿用 Lombok；Controller 输入使用 `@Valid`；Service 以 `IllegalStateException` 表达业务错误；Repository 用 `boolean`/`int` 表达操作结果。
- 生产状态码使用 `HttpStatus` 命名常量；输入错误归 `ValidationExceptionHandler`，全局兜底归 `GlobalExceptionHandler`，管理端业务错误留在所属 Controller。
- 新增 Agent 工具必须登记白名单并保持只读边界；Prompt 放在 `src/main/resources/prompts/`，不要重新内嵌到编排流程。
- `/api/chat/*` 不使用 fallback；Agent 只能依据工具/RAG 数据回答，待复核数据不能推测具体字段。

## 文档索引

> - 目录、分层、数据库、安全、异常、Agent：[`AGENTS-architecture.md`](./AGENTS-architecture.md)
> - 启动、导入、配置、测试、API 快速流程：[`AGENTS-operations.md`](./AGENTS-operations.md)
> - 当前功能、v0.1 M1-M7、整改、下一阶段、历史版本：[`AGENTS-status.md`](./AGENTS-status.md)
> - 数据库表结构与 SQLite 迁移：[`SCHEMA.md`](./SCHEMA.md)
> - API 端点、权限、CSRF、错误处理：[`API.md`](./API.md)
> - API 调用示例、管理员查询、限额和错误码：[`API_GUIDE.md`](./API_GUIDE.md)
> - 项目规划：[`PROJECT_PLAN.md`](./PROJECT_PLAN.md)
> - 协作和 PR 规则：[`CONTRIBUTING.md`](./CONTRIBUTING.md)
> - 发布说明：[`LifeComposer/external/templates/release_notes.html`](./LifeComposer/external/templates/release_notes.html)
