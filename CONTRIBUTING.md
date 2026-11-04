# 协同开发与提 PR 说明

这份说明只规定协作方式，不重新分配项目成员职责。项目负责人决定最终合并；每位成员负责自己分支中的改动、验证和 PR 描述。

## 开始工作

1. 从最新 `main` 创建一个分支，分支只对应一个功能或一个文档工作包，例如 `feat/profile-page`、`docs/data-contract`、`test/recommendation-flow`。
2. 开工前先查看 `AGENTS.md`、`API.md`、`SCHEMA.md` 以及对应的 `plans/` 文件。
3. 一个 PR 只解决一个问题。不要把登录、页面、数据和测试混在同一个 PR 中。

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

## 处理冲突

发现文件归属不清、接口响应不一致或需要改动受保护目录时，先在 PR 中说明路径和原因，等待项目负责人或相关代码维护者确认；不要用强制覆盖或 `git reset --hard` 解决冲突。
