# 协同开发与提 PR 说明

这份说明只规定协作方式，不重新分配项目成员职责。仓库管理员（后端负责人刘正扬）决定最终合并；每位成员负责自己分支中的改动、验证和 PR 描述。

## `main` 分支保护

`main` 已开启分支保护：

- **所有改动必须通过 Pull Request 合并**，不能直接推送到 `main`；
- 禁止强制推送（force push），禁止删除 `main`；
- 仓库管理员在保护规则的绕过名单中，仅用于纯文档的小修正（如错别字、协作说明）；涉及代码、接口、数据库、安全的改动，管理员也走分支加 PR；
- 暂未启用必需的状态检查（仓库还没有 CI），所以 PR 合并前由作者在本地跑 `./mvnw test` 并在 PR 描述里写明结果。

## 开始工作

1. 从最新 `main` 创建一个分支，分支只对应一个功能或一个文档工作包，例如 `feat/profile-page`、`docs/data-contract`、`test/recommendation-flow`。
2. 开工前先查看 `AGENTS.md`、`API.md`、`SCHEMA.md` 以及对应的 `plans/` 文件。
3. 一个 PR 只解决一个问题。不要把登录、页面、数据和测试混在同一个 PR 中。

## 分支与 PR 粒度

"一个 PR 只解决一个问题"不等于"每个提交一个 PR"。按下面的规则控制数量：

- **以"一个可独立验收的功能"为单位**，不是以"一个任务清单条目"或"一次提交"为单位。同一功能的后续修正（例如锁定策略改维度）直接追加提交到原分支；原 PR 已合并后才发现的问题，再开新 PR。
- **同一模块、同一批文件、同一验收方式的小改动合并成一个 PR**。例如"注册限流加 `Retry-After`"和"回收空闲 IP"属于同一个类，应在同一 PR。
- **一个 PR 通常不超过约 400 行有效改动**；超过就拆，但拆的依据是"能否独立合并、独立回滚"，不是凑数量。
- **同时打开的 PR 不超过 3 个**。上一个未合并时，不要从 `main` 再开一串互不相关的分支堆着等审。
- **不要提交后立刻 Revert 再重做**。提 PR 前先本地跑 `./mvnw test`，自查 diff；确需回退，在原分支修正，而不是 Revert + 重做两个提交。
- **合并后删除远程分支**（GitHub 合并页的 `Delete branch`），避免 `feat/auth-*` 长期堆积。
- 分支名统一 `类型/模块-简述`，如 `feat/auth-login-lock`；一个分支对应一个 PR。

### 契约与文档必须同 PR 更新

改了下面这些东西，**同一个 PR** 里必须同步改对应文档，否则不予合并：

| 改动 | 必须同步 |
|---|---|
| 新增/修改表或列 | `SCHEMA.md` |
| 新增/修改接口、状态码、响应头 | `API.md`（必要时 `API_GUIDE.md`） |
| 用户可见行为变化 | `release_notes.html` |
| 时间戳列 | 必须遵守"UTC 文本 `yyyy-MM-dd HH:mm:ss`"约定（见 `API.md`） |

## 文件边界

- 数据库、Agent 和推荐核心代码属于现有后端主干。需要修改时提交接口需求和原因，不要另建用户表、SQLite 数据库、JWT 后端。
- **登录安全模块（v0.1.3 起归王钰博维护，以 PR 修改）**：`Controller/AuthController.java`、`Service/AuthLoginService.java`、`Service/LoginAttemptService.java`、`Service/RegistrationRateLimiter.java`、`Security/` 下全部文件、`UserRepository`/`UserService` 中的认证与账号状态（封禁、锁定、临时密码、`credential_version`）部分、`dto/AuthRegisterRequest.java`、`dto/ApiEnvelope.java`、`external/templates/zhitu_auth.html`、`login.html`、`change_password.html`。其余人修改这些文件需先在 PR/Issue 里说明。管理控制台的封禁按钮界面仍归控制台，但调用同一个 Service，不另写封禁逻辑。
- 不复制 `zhitu-auth` 的 JPA/Spring Boot 3 代码：按它的接口契约（`docs` 见 `API.md`「账号中心」）在本仓库内用 `JdbcTemplate` 实现；单进程、同端口 18000、Session + CSRF，不共享会话库。
- 登录/注册界面是 `/front/login`、`/front/register`（`zhitu_auth.html`，调用 `/api/auth/*`）；管理端入口 `/adminlogin`（`login.html`，仍用 `/api/users/login`）。保持 Session + CSRF。
- 数据成员提交脱敏数据、字段字典和导入报告；不要提交 `data.db`。原始 `样例/` 目录目前是本地资料，协作数据应放到计划指定的可追踪目录。
- 前端成员主要修改 `LifeComposer/external/templates/` 和 `LifeComposer/external/static/`。不要顺手修改 `Security/`（登录安全模块，见上）、Repository、数据库迁移或 API 响应格式。
- 测试成员提交测试矩阵、脱敏夹具和缺陷复现步骤。发现接口缺口时记录缺口，不在测试分支临时复制一个 Controller。

## GitHub Desktop 提 PR

1. `Fetch origin`，确认当前分支基于最新 `main`。
2. 检查变更列表，只保留本次工作包需要的文件。
3. 用一句话提交，例如 `docs: add profile field contract` 或 `feat: extract formal chat page`。
4. Push 分支并创建 Pull Request。
5. PR 描述填写：完成内容、修改文件、依赖接口、验证方法、已知限制、需要其他成员确认的问题。

## PR 描述模板

```markdown
## 完成内容
-

## 修改文件
-

## 依赖的接口或数据契约
-

## 验证
- 命令：
- 页面/用例：
- 截图或脱敏响应：

## 已知限制
-

## 需要确认的问题
-
```

## 必须遵守的安全规则

- 前端请求认证状态使用 `credentials: "include"`；状态变更请求使用现有 CSRF 工具。
- 不在 `localStorage` 保存 JWT、密码、Session 或 API key。
- 不在代码、日志、截图、测试和 PR 中写入真实密码、Cookie、Session、API key 或真实用户信息。
- 不提交 `data.db`。
- 动态页面内容使用安全 DOM API；不要把模型输出直接交给 `innerHTML`。

## 尽量避免冲突

1. **先同步再动手**：每次开工先 `Fetch origin`，从最新 `main` 拉分支。分支尽量一两天内合并，存活越久冲突越多。
2. **合并前再同步一次**：PR 合并前先把最新 `main` 合进自己的分支，在本地解决冲突并跑通测试，不要把冲突留给负责人。
3. **热点文件只追加**：`API.md`、`SCHEMA.md`、`AGENTS-status.md`、`release_notes.html`、`DatabaseInitializer.java`、`application.properties` 几乎每个 PR 都会碰。只在约定位置追加新的一节或几行，不整段重排、不改格式、不顺手整理别人的内容。
4. **不混入无关改动**：格式化整个文件、批量重命名、调整 import 顺序，单独提 PR 并提前告知。
5. **长期分支每天合并一次 `main`**：确实无法在一两天内结束的大功能，每天把 `main` 合进来，冲突就是小块的。

## 处理冲突

发现文件归属不清、接口响应不一致或需要改动受保护目录时，先在 PR 中说明路径和原因，等待仓库管理员或相关代码维护者确认；不要用强制覆盖或 `git reset --hard` 解决冲突。
