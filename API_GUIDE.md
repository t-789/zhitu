# LifeComposer API 调用指南

> 适用版本：**v0.0.7**（2026-09-20）
> 依据来源：`LifeComposer/src/main/java/**` 全部 Controller / DTO / Entity / Service + `WebSecurityConfig` + `application.properties`（逐端点核对，非推测）。
> 配套文档：`API.md`（端点速查清单）、`SCHEMA.md`（数据库表结构）、`AGENTS.md`（工程约定）。

本文档面向**前端 / 联调 / 测试**人员，目标是「照着就能调通」：每个端点给出认证要求、请求参数、字段含义、成功响应、常见失败与状态码。

---

## 目录

1. [项目梗概](#1-项目梗概)
2. [通用约定](#2-通用约定)
3. [快速上手（完整 curl 流程）](#3-快速上手完整-curl-流程)
4. [端点详解](#4-端点详解)
   - [4.1 CSRF](#41-csrf)
   - [4.2 用户管理](#42-用户管理)
   - [4.3 反馈](#43-反馈)
   - [4.4 用户画像](#44-用户画像)
   - [4.5 规划历史](#45-规划历史)
   - [4.6 问答 QA](#46-问答-qa)
   - [4.7 对话 Chat（含 SSE）](#47-对话-chat含-sse)
   - [4.8 加分规则](#48-加分规则-college-credit-rules)
   - [4.9 加分记录](#49-加分记录-credit-activities)
   - [4.10 成长资源库](#410-成长资源库-resources)
   - [4.11 RAG 检索切片](#411-rag-检索切片-rag-chunks)
   - [4.12 能力标签与能力字典](#412-能力标签与能力字典)
   - [4.13 管理控制台](#413-管理控制台-apiadmin)
   - [4.14 页面路由](#414-页面路由)
5. [Agent 内置工具清单](#5-agent-内置工具清单)
6. [枚举与字典](#6-枚举与字典)
7. [配置项与限额](#7-配置项与限额)
8. [错误码总表](#8-错误码总表)
9. [已知注意事项与风险](#9-已知注意事项与风险)

---

## 1. 项目梗概

**基于能力画像的大学生跨学科成长路径规划智能体**（大学生创新创业训练计划 · 创新训练类）。

系统把学生的**能力画像**与结构化的**成长资源库 / 加分规则库 / 知识库**结合，通过 Agent 多轮工具调用给出可执行的成长方向与资源建议。核心链路：

```
能力画像 → 方向识别 → 路径生成 → 资源适配 → Agent 建议
```

| 项 | 内容 |
|----|------|
| 后端 | Spring Boot 4.0.5 + Java 25 |
| 数据库 | SQLite（`data.db`），`JdbcTemplate` 手写 SQL（非 JPA） |
| 认证 | Session-based（`JSESSIONID` Cookie）+ Spring Security |
| CSRF | `XSRF-TOKEN` Cookie + `X-XSRF-TOKEN` 请求头 |
| LLM | OkHttp + Gson，OpenAI 兼容协议；默认 DeepSeek `deepseek-flash` |
| Embedding | 本地 Ollama `POST /v1/embeddings`，模型 `quentinz/bge-small-zh-v1.5:f16` |
| Agent | 多轮 tool-use（5 个只读白名单工具，最多 8 轮）+ SSE 过程展示 |
| 默认端口 | **18000**（`server.port=18000`） |
| 规模 | **64 个 REST 端点** + 18 个页面路由 + 4 个错误页；336 个自动化测试 |

启动方式见 `LifeComposer/run.sh`（自动加载 `.env`，注入 LLM 配置）。**空库首次启动**必须提供环境变量 `LIFECOMPOSER_INITIAL_ADMIN_PASSWORD`（≥12 字符），否则 fail-fast——这是刻意设计，不是 bug。

---

## 2. 通用约定

### 2.1 基础地址与编码

```
Base URL: http://localhost:18000
所有请求体: Content-Type: application/json，UTF-8
```

### 2.2 认证方式

登录成功后服务端下发 `JSESSIONID` Cookie，后续请求携带该 Cookie 即视为已登录。

```
认证状态 = JSESSIONID Cookie
```

**未登录访问受保护端点返回 `403`（不是 401）**：因为 `/api/users/current` 等路径在 Spring Security 过滤器链上就被拦截，而本项目未配置自定义 `AuthenticationEntryPoint`，走的是 Spring Security 默认行为（`Http403ForbiddenEntryPoint` → `sendError(403)` → Spring Boot 默认错误处理）。

- 403 响应体：由 Spring Boot 默认错误处理生成 `{timestamp,status,error,path}`（`server.error.include-message=never`，**不含 message**）；页面请求则渲染错误页。
- 控制器里写的 `401 {"error":"未登录"}` / `401 用户未登录` 分支，只在**过滤器链已放行、但会话缺少 `user` 属性**等边界场景才会出现。
- `401` 的主要来源是会话守卫过滤器（见 [2.5](#25-会话守卫401-的三个来源)）。

### 2.3 CSRF（**POST/PUT/DELETE 全部需要**）

服务端启用 Spring Security CSRF，使用 `CookieCsrfTokenRepository`：

| 项 | 值 |
|----|----|
| Token 读取 | `GET /api/csrf`，同时写入可读 Cookie `XSRF-TOKEN` |
| 请求头名 | `X-XSRF-TOKEN`（兼容默认名 `X-CSRF-TOKEN`，由 `XsrfHeaderAliasFilter` 映射） |
| 参数名 | `_csrf`（表单场景） |
| 豁免方法 | `GET` / `HEAD` / `OPTIONS` |

> **关键点：CSRF 与"是否公开端点"无关。**`POST /api/users/login`、`POST /api/users/register` 虽然是公开端点，**仍然必须带 CSRF token**，否则 403。这是最容易踩的坑。

前端约定：加载 `external/static/csrf.js`，它包装了 `window.fetch`，对 POST/PUT/DELETE 自动附加 `X-XSRF-TOKEN`。

### 2.4 响应体形态（三种，务必区分）

| 形态 | 出现在 | 说明 |
|------|--------|------|
| **JSON 对象/数组** | 大多数端点（返回 `Map`、DTO、`List`） | 正常 `application/json` |
| **纯文本字符串** | `ResponseEntity.ok("...")` / `body(e.getMessage())` 类端点 | 如 `注册成功`、`登出成功`、`权限不足`、`用户档案不存在`。**不是 JSON**，前端不要直接 `JSON.parse` |
| **空响应体** | 无 body 的 404（未知 `/api/**` 路径） | `GlobalExceptionHandler` 对 API 返回裸 404 |

被标记为「文本返回」的端点在下面章节中会显式注明。

### 2.5 会话守卫（401 的三个来源）

`SessionCredentialGuardFilter` 挂在过滤器链 `AuthorizationFilter` 之前，每个请求校验凭证版本：

| 触发条件 | HTTP | 响应体 |
|----------|------|--------|
| 密码已在别处变更（`credential_version` 不匹配） | `401` | `{"error":"SESSION_EXPIRED","message":"密码已变更，请重新登录"}` |
| 会话处于「临时密码待修改」状态却访问其它端点 | `401` | `{"error":"PASSWORD_CHANGE_REQUIRED","message":"必须先修改临时密码才能继续使用"}` |
| 临时密码已过期 | `401` | `{"error":"TEMP_PASSWORD_EXPIRED","message":"临时密码已过期，请联系管理员重新下发"}` |

临时密码状态下**仅允许**这 5 个端点 + 错误页：
`POST /api/users/password`、`GET /api/users/current`、`POST /api/users/logout`、`GET /api/csrf`、`GET /front/change-password`。

### 2.6 时间格式

`Timestamp` / `createdAt` 等字段经 Jackson 序列化为 **ISO-8601 字符串**（如 `2026-09-20T12:34:56.789+00:00`），不是 epoch 数字。`Instant` 类型用 `toString()`（如 `thinking_start` 事件的 `startedAt`）。

### 2.7 分页信封（仅管理控制台）

所有 `/api/admin/**` 列表端点统一返回：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 50,
  "total": 123,
  "totalPages": 3
}
```

`page ≥ 1`（上限 100000），`pageSize ∈ [1, 200]`，默认 50。

### 2.8 权限级别

| 级别 | 含义 |
|------|------|
| 公开 | 无需登录、无需 CSRF 之外的任何凭证 |
| 已登录 | 需有效 `JSESSIONID` |
| ADMIN | 需 `users.type = 2`（`ROLE_ADMIN`），过滤器链 + 控制器双重校验 |

**公开端点（6 个）**：`GET /api/csrf`、`POST /api/users/register`、`POST /api/users/login`、`POST /api/feedback/submit`、`POST /api/feedback/system-error`、`GET /api/qa/health`

**Bot 防护**：空 `User-Agent` 或命中爬虫关键词的请求**一律 403**，且该策略**优先于**输入校验错误（即使请求同时有非法 JSON，也是 403 而非 400）。联调时请始终带 `User-Agent`。

### 2.9 所有权规则

访问用户私有数据（画像 / 加分记录 / 规划历史 / 对话）时，服务端**强制**通过 SecurityContext 解析当前用户，**禁止跨用户读取**。跨用户访问已有资源返回 400 + 文本（如 `无权访问此加分记录`），而非 403。

### 2.10 部署注意：反向代理与客户端地址

登录锁定、注册限流、`login_log` 审计都按**客户端地址**分桶，地址取自 `HttpServletRequest.getRemoteAddr()`。放在 nginx / Caddy 之类的反向代理后面时，`getRemoteAddr()` 拿到的是**代理地址**，后果是：

- 注册限流从"每 IP 每分钟 10 次"退化成**全站共享**每分钟 10 次；
- 登录第一层（账号 + IP）失去意义，所有人共用一个 IP 桶；
- `login_log.ip_address` 全是 `127.0.0.1` 这类代理地址，审计查不出是谁。

**两种处理方式，选一种**：

1. **应用直连**（默认）：不设 `server.forward-headers-strategy`，代理只做 TLS 终结与端口转发、不改写地址。此时限流按代理地址分桶，只适合单机内网。
2. **信任代理**（生产推荐）：

   ```properties
   server.forward-headers-strategy=framework
   ```

   同时确保**只有**反向代理能访问应用端口（否则任何人都能伪造 `X-Forwarded-For` 绕过限流）。代理需设置 `X-Forwarded-For` / `X-Forwarded-Proto`（nginx：`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`）。

> 这是部署配置，不在代码里硬编码。切换后请重启并确认 `login_log.ip_address` 记录的是真实客户端地址。

**上线安全开关清单**（漏一项就等于没做）：

| 配置 | 生产值 | 漏配的后果 |
|------|--------|-----------|
| `app.security.admin-intranet-only` | `true` | 管理员账号暴露在外网，只受第一层锁定，换 IP 即可慢慢猜密码；管理员超阈值只告警不上锁，等于没有兜底 |
| `server.forward-headers-strategy` | `framework` + 只有代理能连应用端口 | 上面整节失效：限流按代理分桶、IP 维度锁定失去意义 |
| `app.security.login-trusted-ip-days` | 保持 `30`（按需调整） | 调成 0 等于取消"可信 IP 豁免"，攻击者用两个 IP 就能把受害者锁在门外 |

---

## 3. 快速上手（完整 curl 流程）

```bash
BASE=http://localhost:18000
UA='Mozilla/5.0 (compatible; LifeComposer-dev)'
JAR=/tmp/lc-cookies.txt

# ① 取 CSRF token（同时种下 XSRF-TOKEN Cookie）
TOKEN=$(curl -s -c $JAR -H "User-Agent: $UA" $BASE/api/csrf \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# ② 注册（字段：3-20 字符用户名；密码 1-50 字符）
curl -s -b $JAR -c $JAR -X POST $BASE/api/users/register \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" -H "User-Agent: $UA" \
  -d '{"username":"alice","password":"pass123"}'
# → 注册成功        （纯文本）

# ③ 登录（会重建 Session，并返回 type / passwordChangeRequired）
curl -s -b $JAR -c $JAR -X POST $BASE/api/users/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" -H "User-Agent: $UA" \
  -d '{"username":"alice","password":"pass123"}'
# → {"message":"登录成功","username":"alice","type":1,
#    "passwordChangeRequired":false,"tempPasswordExpiresAt":null}

# ④ 登录后建议重新取一次 token（会话已重建），再带 Cookie 调业务接口
TOKEN=$(curl -s -b $JAR -c $JAR -H "User-Agent: $UA" $BASE/api/csrf \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# ⑤ 写画像（PUT 是全量覆盖；JSON 类字段要传"字符串化的 JSON"）
curl -s -b $JAR -X PUT $BASE/api/profiles/me \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" -H "User-Agent: $UA" \
  -d '{"college":"计算机学院","major":"软件工程","grade":"大二",
       "skillsJson":"[\"Java\",\"SQL\"]","interestsJson":"[\"AI\"]",
       "experiencesJson":"[]","preferencesJson":"{}",
       "availableTime":"每周 10 小时","goals":"[\"参加蓝桥杯\"]"}'

# ⑥ 读画像
curl -s -b $JAR -H "User-Agent: $UA" $BASE/api/profiles/me

# ⑦ AI 对话（非流式）
curl -s -b $JAR -X POST $BASE/api/chat/send \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" -H "User-Agent: $UA" \
  -d '{"message":"我适合参加什么竞赛？"}'

# ⑧ SSE 流式（-N 关闭缓冲）
curl -N -b $JAR -X POST $BASE/api/chat/stream \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" -H "User-Agent: $UA" \
  -d '{"message":"帮我规划一下本学期"}'

# ⑨ 登出
curl -s -b $JAR -X POST $BASE/api/users/logout -H "X-XSRF-TOKEN: $TOKEN" -H "User-Agent: $UA"
```

---

## 4. 端点详解

### 4.1 CSRF

#### `GET /api/csrf` — 获取 CSRF token
- **认证**：公开
- **请求参数**：无
- **成功响应** `200`（JSON）：
```json
{
  "headerName": "X-XSRF-TOKEN",
  "parameterName": "_csrf",
  "token": "a1b2c3d4-...."
}
```
同时响应头写入可读 Cookie `XSRF-TOKEN`（`Path=/`、`SameSite=Lax`、非 HttpOnly，HTTPS 部署时 `Secure`）。

---

### 4.2 用户管理

#### `POST /api/users/register` — 注册
- **认证**：公开（**需 CSRF**）
- **限流**：每 IP 每分钟 `10` 次（`app.security.register-per-minute-per-ip`）
- **请求体**：

| 字段 | 类型 | 必填 | 约束 |
|------|------|------|------|
| `username` | string | ✅ | 3–20 字符 |
| `password` | string | ✅ | 1–50 字符 |

```json
{ "username": "alice", "password": "pass123" }
```

- **成功响应** `200`：纯文本 `注册成功`
- **常见失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `400` | `{"username":"用户名长度必须在3-20个字符之间"}` | Bean Validation 字段错误（字段→消息 map） |
| `400` | `注册失败，用户名可能已存在`（文本） | 用户名占用 |
| `403` | — | 缺 CSRF token / 空 User-Agent |
| `429` | `{"error":"REGISTER_RATE_LIMIT","message":"注册请求过于频繁，请稍后再试","retryAfterSeconds":42}` | 触发限流，响应头带 `Retry-After` |

> ⚠️ 注册**没有密码强度校验**（仅长度 1–50）。强密码规则只作用于管理员与「管理员下发的临时密码」。

#### `POST /api/users/login` — 登录
- **认证**：公开（**需 CSRF**）
- **限流**：同一 `用户名+IP` 连续失败 `5` 次 → 锁定 `900` 秒（15 分钟，内存态）
- **请求体**：`username`（3–20）、`password`（1–50）
- **成功响应** `200`（JSON）：

```json
{
  "message": "登录成功",
  "username": "alice",
  "type": 1,
  "passwordChangeRequired": false,
  "tempPasswordExpiresAt": null
}
```

- **副作用**：销毁登录前旧 Session 并新建（防 Session Fixation）；下发新的 `JSESSIONID`；设置 `XSRF-TOKEN`。
- **常见失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `400` | `{"error":"BAD_CREDENTIALS","message":"登录失败，用户名或密码错误"}` | 用户名或密码错误 |
| `400` | `{"error":"LOGIN_REJECTED","message":"账户被封禁至2026-10-05 16:45:17.14"}` | 账号被封禁且未到期 |
| `403` | `{"error":"TEMP_PASSWORD_EXPIRED","message":"临时密码已过期，请联系管理员重新下发"}` | 管理员下发的临时密码已过期 |
| `429` | `{"error":"LOGIN_LOCKED","message":"登录失败次数过多，请稍后再试","retryAfterSeconds":900}` | 触发失败锁定，响应头带 `Retry-After` |

> `passwordChangeRequired: true` 时，该会话**只能**访问改密相关端点，必须先调 `POST /api/users/password`。

#### `POST /api/users/logout` — 登出
- **认证**：已登录（需 CSRF）
- **成功响应** `200`：纯文本 `登出成功`（同时清空 SecurityContext 并使 Session 失效）

> 另有 Spring Security 原生 `POST /logout`（登出后 302 到 `/login?logout`），业务前端请用 `/api/users/logout`。

#### `GET /api/users/current` — 当前用户信息
- **认证**：已登录
- **成功响应** `200`（JSON）：

```json
{
  "id": 3,
  "username": "alice",
  "type": 1,
  "avatar": null,
  "isBanned": false,
  "banEndTime": null
}
```

> 注意字段名是 `isBanned`（与 `/api/users/all` 的 `banned` 不同）；`type` 为数字。
> 未登录由过滤器链拦截 → `403`。

#### `GET /api/users/all` — 用户列表
- **认证**：ADMIN
- **成功响应** `200`：数组，**已剔除 `passwordHash`**

```json
[{
  "id": 3, "username": "alice", "type": 1, "avatar": null,
  "banned": false, "banEndTime": null,
  "createdAt": "2026-09-20T10:00:00.000+00:00",
  "updatedAt": "2026-09-20T10:00:00.000+00:00"
}]
```

- **失败** `403`：`{"error":"权限不足"}`

#### `PUT /api/users/{userId}/grant-admin` — 授予管理员
- **认证**：ADMIN（需 CSRF）
- **成功响应** `200`：纯文本 `用户3已被赋予管理员权限`
- **失败** `400`：纯文本 `用户3不存在`；`403`：`{"error":"权限不足"}`

#### `PUT /api/users/{userId}/revoke-admin` — 撤销管理员
- **认证**：ADMIN（需 CSRF）
- **成功响应** `200`：纯文本 `用户3已被撤销管理员权限`
- **失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `404` | `{"error":"NOT_FOUND","message":"用户不存在"}` | 用户不存在 |
| `409` | `{"error":"LAST_ADMIN_PROTECTED","message":"系统必须保留至少一个可登录管理员，撤销管理员权限操作已被拒绝"}` | 最后一个可登录管理员 |

> 并发安全：数量条件写在 `UPDATE ... WHERE` 内 + `transaction_mode=immediate` 写锁，`n` 个并发撤销最多成功 `n-1` 个。

#### `PUT /api/users/{userId}/ban` — 封禁
- **认证**：ADMIN（需 CSRF）
- **请求体**：`{"banTime": "1d"}`

| `banTime` 写法 | 含义 |
|----------------|------|
| `30m` | 30 分钟（**`m` = 30 天/个单位，不是分钟**） |
| `1d` / `7d` | N 天 |
| `1h` | N 小时 |
| `1y` | N 年（按 365 天） |
| `0` | **永久封禁** |
| 可组合 | 如 `1y30d` 累加 |

> ⚠️ 单位里 **`m` 表示"月"（30 天）**，没有"分钟"单位。想封 30 分钟请用 `0.5h`… 实际上不支持小数，请用 `1h` 等。

- **成功响应** `200`：纯文本 `用户封禁操作完成`
- **失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `400` | `{"error":"INVALID_INPUT","message":"封禁时间不能为空"}` | body 缺失 / `banTime` 空白 |
| `400` | `{"error":"INVALID_INPUT","message":"封禁时间格式不正确"}` | 单位非法（如 `1x`） |
| `404` | `{"error":"NOT_FOUND","message":"用户不存在"}` | 用户不存在 |
| `409` | `{"error":"LAST_ADMIN_PROTECTED",...}` | 最后可登录管理员 |

- **自动解封**：封禁到期后该用户下次登录时自动解除。

#### `PUT /api/users/{userId}/unban` — 解封
- **认证**：ADMIN（需 CSRF）
- **成功响应** `200`：纯文本 `用户解封操作完成`
- **失败** `400`：纯文本 `解封操作失败`

#### `POST /api/users/admin/reset-password/{userId}` — 下发临时密码
- **认证**：ADMIN（需 CSRF）
- **请求体**：`{"newPassword":"InitTemp#2026x"}`

| 目标账号 | 最短长度 | 其它规则 |
|----------|----------|----------|
| 管理员账号 | **12** 字符 | ≤72、无空白字符、不在弱密码表、非单字符重复 |
| 普通用户账号 | **8** 字符 | 同上 |

- **成功响应** `200`（**绝不回显密码**）：

```json
{
  "message": "临时密码已下发，用户首次登录后必须立即修改",
  "passwordChangeRequired": true,
  "tempPasswordExpiresAt": "2026-09-21T10:00:00.000+00:00"
}
```

- **失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `400` | `{"error":"WEAK_PASSWORD","message":"密码长度至少为 12 个字符"}` | 不满足强度策略 |
| `400` | `{"error":"RESET_FAILED","message":"密码重置失败"}` | 写库失败 |
| `404` | `{"error":"用户不存在"}` | 目标用户不存在 |
| `403` | `{"error":"权限不足"}` | 非管理员 |

- **副作用**：`password_reset_required=1`、`temp_password_expires_at=now+TTL`（默认 1440 分钟）、`credential_version+1` → **该用户所有旧会话立即失效**（401 `SESSION_EXPIRED`）。

#### `POST /api/users/password` — 自助修改密码
- **认证**：已登录（需 CSRF）；**这是临时密码会话唯一可用的业务端点**
- **请求体**：`{"currentPassword":"old","newPassword":"newpass123"}`
- **成功响应** `200`：`{"message":"密码已更新"}`
- **失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `400` | `{"error":"PASSWORD_REJECTED","message":"当前密码不正确"}` | 当前密码错误 |
| `400` | `{"error":"PASSWORD_REJECTED","message":"新密码不能与当前密码相同"}` | 新旧相同 |
| `400` | `{"error":"PASSWORD_REJECTED","message":"密码长度至少为 8 个字符"}` | 不满足策略（管理员按 12） |
| `401` | `{"error":"UNAUTHORIZED","message":"用户未登录"}` | 无会话 |

- **副作用**：清除临时密码状态、`credential_version+1`。**发起修改的会话会被重新盖章因此保留登录**，该账号其它设备上的会话全部失效。

#### `POST /api/users/admin/chat-quota/reset/{userId}` — 重置当日聊天额度
- **认证**：ADMIN（需 CSRF）；**无日期参数**，只能重置当前 Asia/Shanghai 自然日
- **成功响应** `200`（JSON）：

```json
{
  "userId": 3,
  "usageDate": "2026-09-20",
  "usedToday": 0,
  "remainingToday": 100,
  "dailyLimit": 100,
  "minuteLimit": 5,
  "minuteRemaining": 5
}
```

- **失败**：`400 {"error":"用户不存在"}`；`403 {"error":"权限不足"}`
- **副作用**：写审计日志 `AUDIT event=admin_reset_chat_quota`；同时重置该用户的分钟计数。

---

### 4.3 反馈

#### `POST /api/feedback/submit` — 提交用户反馈
- **认证**：公开（需 CSRF）；已登录则自动记录用户，未登录记为 `Anonymous` / `userId=-1`
- **请求体**：

| 字段 | 必填 | 说明 |
|------|------|------|
| `content` | ✅ | 反馈正文，空白 → 400 |
| `url` | ❌ | 来源页面 |
| `userAgent` | ❌ | 客户端 UA |
| `stackTrace` | ❌ | 该端点不落库（仅 `system-error` 用） |

- **成功响应** `200`：`{"message":"反馈提交成功"}`
- **失败** `400`：`{"error":"反馈内容不能为空"}` / `{"error":"反馈提交失败"}`

#### `POST /api/feedback/system-error` — 上报系统错误
- **认证**：公开（需 CSRF）
- **请求体**：`content`（空白时落库为 `未提供错误详情`）、`url`、`userAgent`、`stackTrace`
- **成功响应** `200`：`{"message":"系统错误报告已提交"}`
- **失败** `400`：`{"error":"系统错误报告提交失败"}`

#### `GET /api/feedback/all` — 全部反馈
- **认证**：ADMIN
- **成功响应** `200`：`Feedback[]`

```json
[{
  "id": 1, "userId": 3, "username": "alice",
  "content": "这个功能很好用", "type": "user",
  "url": "/chat", "userAgent": "Mozilla/5.0...", "stackTrace": null,
  "createTime": "2026-09-20T10:00:00.000+00:00",
  "resolved": false, "resolvedBy": null, "resolvedTime": null
}]
```

- **失败** `403`：`{"error":"权限不足"}`

#### `GET /api/feedback/type/{type}` — 按类型过滤
- **认证**：ADMIN
- **路径参数**：`type` ∈ `user` | `system`
- **成功响应**：同上数组

#### `POST /api/feedback/{id}/resolve` — 标记处理状态
- **认证**：ADMIN（需 CSRF）
- **请求体（可选）**：`{"resolved": true}`；**body 缺失或字段缺失时默认 `true`**
- **成功响应** `200`：`{"message":"反馈状态更新成功"}`
- **失败** `400`：`{"error":"反馈状态更新失败"}`
- **副作用**：写入 `resolvedBy` = 操作管理员用户名

---

### 4.4 用户画像

#### `GET /api/profiles/me` — 获取当前用户画像
- **认证**：已登录
- **成功响应** `200`：`UserProfileDto`（**只含字段，不含 id/userId/时间戳**）

```json
{
  "college": "计算机学院",
  "major": "软件工程",
  "grade": "大二",
  "studentId": "2023211234",
  "skillsJson": "[\"Java\",\"SQL\"]",
  "interestsJson": "[\"AI\"]",
  "experiencesJson": "[]",
  "preferencesJson": "{}",
  "availableTime": "每周 10 小时",
  "goals": "[\"参加蓝桥杯\"]"
}
```

- **失败**：`404` 纯文本 `用户档案不存在`（尚未填写画像）；未登录 → `403`

> **JSON 字段是字符串**：`skillsJson` / `interestsJson` / `experiencesJson` / `preferencesJson` / `goals` 在传输层都是 **string**，内容是 JSON 文本。前端发送前要 `JSON.stringify(...)`，接收后要 `JSON.parse(...)`。

#### `PUT /api/profiles/me` — 新建或更新画像（Upsert）
- **认证**：已登录（需 CSRF）
- **请求体**：`UserProfileDto`（全部 10 个字段均为可选，但见下方警告）
- **成功响应** `200`：返回**落库后重新读取**的画像（同 `GET` 结构）
- **语义**：**全量覆盖（Upsert）**，不是 PATCH。

> ⚠️ **未提交的字段会被写成 `NULL`**（服务端逐字段赋值后整体 upsert）。前端做局部更新时，必须先 `GET` 再合并，否则会误删已有数据。

- **失败**：`400` 字段校验 map（当前 DTO 无校验注解，实际很少触发）；`401` 文本 `用户未登录`（边界场景）

---

### 4.5 规划历史

#### `GET /api/planning/history` — 当前用户 AI 交互历史列表
- **认证**：已登录
- **成功响应** `200`：`PlanningHistoryDto[]`（按仓库默认排序）

```json
[{
  "id": 12,
  "type": "QA",
  "requestJson": "{\"message\":\"...\"}",
  "responseJson": "{\"answer\":\"...\"}",
  "provider": "deepseek",
  "model": "deepseek-flash",
  "status": "SUCCESS",
  "errorMessage": null,
  "createdAt": "2026-09-20T10:00:00.000+00:00"
}]
```

- `type`：目前由 `QaService` 写入 `QA`；`status` ∈ `SUCCESS` | `FAILED` | `MOCKED`
- **失败**：`401` 文本 `用户未登录`（边界）；未登录 → `403`

#### `GET /api/planning/history/{id}` — 历史详情
- **认证**：已登录（**仅本人**）
- **成功响应** `200`：单个 `PlanningHistoryDto`
- **失败**：`400` 纯文本 `规划记录不存在` / `无权访问此记录`

---

### 4.6 问答 QA

> QA 与 Chat 是**两条独立链路**：QA 是单轮问答且**保留 mock fallback**；Chat 是 Agent 多轮 tool-use 且**禁用 fallback**。

#### `GET /api/qa/health` — LLM 健康检查
- **认证**：公开
- **成功响应** `200`：

```json
{
  "status": "ok",
  "provider": "deepseek",
  "model": "deepseek-flash",
  "enabled": true,
  "apiReachable": true,
  "reason": null,
  "mocked": false,
  "timestamp": "2026-09-20T10:00:00Z"
}
```

| 字段 | 取值 |
|------|------|
| `status` | `ok`（可用）/ `unavailable`（启用但探测失败） |
| `reason` | `null` / `disabled` / 探测失败原因 |
| `mocked` | `!enabled \|\| !apiReachable` |

#### `POST /api/qa/ask` — 单轮提问
- **认证**：已登录（需 CSRF）
- **请求体**：

| 字段 | 必填 | 说明 |
|------|------|------|
| `message` | ✅ | ≤ 5000 字符 |
| `useCase` | ❌ | 默认 `qa`；必须是已配置的 useCase 名（`qa`/`planning`/`profile`/`sql`/`chat`），否则 400 |
| `conversationId` | ❌ | 会话标识（当前不参与逻辑） |
| `metadata` | ❌ | 附加 JSON 字符串 |

- **成功响应** `200`：`QaResponse`

```json
{
  "answer": "建议你从……",
  "mocked": false,
  "provider": "deepseek",
  "model": "deepseek-flash",
  "historyId": 34,
  "timestamp": 1789000000000,
  "error": null
}
```

- **失败**：
  - `400` `{"error":"未知的 useCase: xxx"}`（显式传了未配置的 useCase）
  - `403` 未登录（过滤器链）
  - LLM 失败时**不报错**，而是返回 `mocked:true`、`provider:"mock"`、`answer` 前缀 `[Mock]`，并写一条 `status=FAILED` 的历史

> `timestamp` 在 QA 响应里是 **epoch 毫秒数字**（与其它端点的 ISO 字符串不同）。

---

### 4.7 对话 Chat（含 SSE）

**四个端点，共享同一套 Agent 多轮 tool-use 逻辑与配额。**

**配额（每用户）**：`5 次/分钟` + `100 次/天`（Asia/Shanghai 自然日）。
`/api/chat/send` 与 `/api/chat/stream` **每次请求各计 1 次**；被 429 拒绝不扣减；LLM 失败/流中断/客户端断开仍保留已准入的用量。

**`max_tokens`**：服务端强制上限 `2048`，客户端传更大值会被截断（下限 1）。

**上下文窗口（v0.1.2）**：数据库保留全部历史，但发给 LLM 的只有最近 `15` 条用户消息与 AI 最终回答（`app.security.chat-context-messages`），窗口内的工具调用/结果随之发送、不计数。`/api/chat/history` 仍返回全部。

**并发（v0.1.2）**：同一用户同时只允许一个 AI 请求（含画像确认续答），否则 `409 CHAT_IN_PROGRESS`，不扣额度；流式对话全局并发上限 `32`，已满返回 `503 CHAT_SERVER_BUSY` 并退还额度。

#### `POST /api/chat/send` — 非流式对话
- **认证**：已登录（需 CSRF）
- **请求体**：

| 字段 | 必填 | 约束 |
|------|------|------|
| `message` | ✅ | 非空，≤ 5000 字符 |
| `maxTokens` | ❌ | 整数，服务端截断到 [1, 2048] |

- **成功响应** `200`：`ChatResponse`

```json
{
  "role": "assistant",
  "content": "根据你的画像，建议……",
  "createTime": "2026-09-20T18:30:12.345",
  "mocked": false,
  "error": null
}
```

- **常见失败**：

| 状态 | 响应体 | 原因 |
|------|--------|------|
| `400` | `{"message":"消息长度不能超过5000字符"}` | 校验失败 |
| `403` | — | 未登录 / 缺 CSRF |
| `409` | `{"error":"CHAT_IN_PROGRESS","message":"上一条消息还在处理中，请稍候"}` | 同一用户已有 AI 请求在处理（不扣额度） |
| `429` | 见下方配额体 | 分钟或日额度用尽 |
| `503` | `{"error":"LLM_UNAVAILABLE","message":"LLM 不可用，请稍后重试"}` | API key 缺失 / HTTP 非 2xx / 超时 / 格式错误（尚未向用户输出任何内容时退还日额度，不退分钟令牌） |
| `503` | `{"error":"MAX_TOOL_ROUNDS", ...}` | 工具调用超过 8 轮（不退额度） |

**429 配额响应体**（响应头带 `Retry-After`）：

```json
{
  "error": "CHAT_MINUTE_LIMIT",
  "message": "聊天过于频繁，请稍后再试",
  "retryAfterSeconds": 37,
  "dailyLimit": 100,
  "usedToday": 12,
  "remainingToday": 88,
  "minuteLimit": 5,
  "minuteRemaining": 0
}
```

`error` 两种取值：`CHAT_MINUTE_LIMIT`（分钟超限）、`CHAT_DAILY_LIMIT`（`message` = `今日聊天额度已用完`，`retryAfterSeconds` = 距次日 0 点秒数）。

#### `POST /api/chat/stream` — SSE 流式对话
- **认证**：已登录（需 CSRF）
- **请求体**：同 `/api/chat/send`
- **成功响应** `200`，`Content-Type: text/event-stream`

**准入失败时返回普通 JSON（不是 SSE）**：配额不足返回 `429` + 上述配额体；同一用户已有请求返回 `409 CHAT_IN_PROGRESS`；全局流式并发已满返回 `503 {"error":"CHAT_SERVER_BUSY","message":"当前对话人数较多，请稍后重试"}`（额度退还）；LLM 不可用则在流内发 `error` 事件。

**SSE 事件表**（事件名 + `data` 结构）：

| 事件名 | data 字段 | 说明 |
|--------|-----------|------|
| `thinking_start` | `startedAt`（ISO Instant 字符串） | 首次收到 `reasoning_content` 才发 |
| `thinking_tick` | `elapsedSeconds`（number） | 可选，思考计时心跳 |
| `thinking_end` | `elapsedMs`（number） | reasoning 结束（`</think>` / 首个 `content` / 流结束 / 报错） |
| `tool_call` | `callId`, `name`, `description`, `arguments`（JSON **字符串**） | 模型决定调用工具，先发事件后落库 |
| `tool_result` | `callId`, `name`, `ok`（bool）, `result`（JSON **字符串**） | 工具执行结果 |
| `token` | `delta`（string） | 正文增量 |
| `assistant_message` | `content`, `createTime` | 完整回答（落库前发送） |
| `error` | `code`, `message` | 如 `LLM_UNAVAILABLE` / `MAX_TOOL_ROUNDS` / `INTERNAL_ERROR` |
| `done` | `{}` | 流正常结束 |

**前端接入示例**：

```js
const res = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
  body: JSON.stringify({ message: '帮我规划本学期' })
});
if (res.status === 429) { /* JSON 配额体，非 SSE */ }
// 服务端为 POST SSE，需手动解析 text/event-stream
```

**持久化与断开语义（v0.0.4+）**：
- 用户消息先落库；每次工具调用落 **2 行**（`assistant` 的 `tool_call` JSON + `tool` 的 `tool_result` JSON），二者**成对原子写入**；最终回答在完整送达后才落库。
- 思考计时状态、未完成的 assistant 文本**不落库**。
- 浏览器断开时**不会**留下未送达或不成对的 tool/assistant 行，但已完整写入的 user 消息和已送达的工具调用记录会保留。
- 同一轮的多个工具调用用 `turnId` 分组，重建上下文时合并为一条带多个 `tool_calls` 的 assistant 消息 + 多条 tool 结果，符合 OpenAI/DeepSeek 协议。

#### `GET /api/chat/history` — 对话历史
- **认证**：已登录
- **成功响应** `200`：`ChatMessage[]`（按时间稳定排序，**包含 user / assistant / tool 三种行**）

```json
[{
  "id": 101,
  "userId": 3,
  "role": "user",
  "content": "帮我规划本学期",
  "createTime": "2026-09-20T18:30:00.000+00:00"
}, {
  "id": 102,
  "userId": 3,
  "role": "assistant",
  "content": "{\"type\":\"tool_call\",\"turnId\":\"...\",\"name\":\"get_user_profile\",\"callId\":\"call-1\",\"arguments\":{}}",
  "createTime": "2026-09-20T18:30:01.000+00:00"
}]
```

> `role=assistant` 的行若 `content` 以 `{"type":"tool_call"...}` 形式出现，是**工具调用载荷**而非自然语言回答；前端渲染时建议按 `type` 字段分流。

#### `DELETE /api/chat/context` — 清空对话上下文
- **认证**：已登录（需 CSRF）
- **成功响应** `200`：`{"deleted": 42}`
- **注意**：该用户的消息会被**物理删除**，并直接影响后续 LLM 上下文（Agent 每轮把历史消息作为多轮上下文）。

---

### 4.8 加分规则 (College Credit Rules)

#### `GET /api/college-credit-rules` — 规则列表
- **认证**：已登录
- **查询参数（全部可选）**：

| 参数 | 取值 |
|------|------|
| `college` | 学院名（模糊匹配） |
| `creditType` | `graduation`（双创分）/ `recommendation`（保研加分） |
| `category` | 见 [6. 枚举](#6-枚举与字典) |

- **成功响应** `200`：`CollegeCreditRuleDto[]`

```json
[{
  "id": 1, "college": "信息与通信工程学院", "creditType": "graduation",
  "category": "competition", "compLevel": "A", "compName": "全国大学生电子设计竞赛",
  "awardTier": "first", "credits": 4.0, "categoryCap": 10.0,
  "teamFormula": "队长×1.0，队员×0.6", "studentCohort": "2023级起",
  "docSource": "信通院创新实践活动加分办法", "levelsJson": "[...]",
  "notes": "...", "createdAt": "2026-09-05T00:00:00.000+00:00"
}]
```

- **失败** `400`：纯文本 `无效的加分类型: xxx，有效值: [...]`

#### `GET /api/college-credit-rules/{id}` — 规则详情
- **认证**：已登录
- **成功响应** `200`：单个 DTO
- **失败** `400`：纯文本 `加分规则不存在`

#### `POST /api/college-credit-rules` — 新建规则
- **认证**：ADMIN（需 CSRF；过滤器链 + 控制器双重校验）
- **请求体**：

| 字段 | 必填 | 校验 |
|------|------|------|
| `college` | ✅ | 非空 |
| `creditType` | ✅ | `graduation` / `recommendation` |
| `category` | ✅ | 见枚举 |
| `credits` | ✅ | 非空且 ≥ 0 |
| `compLevel` | ❌ | 填了就必须在枚举内 |
| `awardTier` | ❌ | 填了就必须在枚举内 |
| `categoryCap` | ❌ | ≥ 0 |
| `compName`, `teamFormula`, `studentCohort`, `docSource`, `levelsJson`, `notes` | ❌ | 自由文本 |

- **成功响应** `200`：返回落库后重新读取的 DTO（含生成的 `id` / `createdAt`）
- **失败**：`400` 字段校验 map 或纯文本业务错误（`分值为空或为负数` 等）；`403` 文本 `需要管理员权限`

---

### 4.9 加分记录 (Credit Activities)

> 全部端点**仅操作当前登录用户自己的记录**。

#### `GET /api/credit-activities` — 我的加分记录
- **认证**：已登录
- **成功响应** `200`：`CreditActivityDto[]`

```json
[{
  "id": 7, "ruleId": 1, "creditType": "graduation", "category": "competition",
  "compName": "蓝桥杯", "compLevel": "national", "awardTier": "second",
  "credits": 2.0, "obtainedDate": "2026-05-20",
  "certificateRef": "cert/2026/lanqiao.pdf",
  "verified": 0, "notes": "", "createdAt": "2026-09-20T10:00:00.000+00:00"
}]
```

- **失败**：`401` 文本 `用户未登录`（边界）；未登录 → `403`

#### `GET /api/credit-activities/{id}` — 记录详情
- **认证**：已登录（仅本人）
- **失败** `400` 纯文本：`加分记录不存在` / `无权访问此加分记录`

#### `POST /api/credit-activities` — 新建记录
- **认证**：已登录（需 CSRF）
- **请求体**：`CreditActivityDto`

| 字段 | 必填 | 校验 |
|------|------|------|
| `creditType` | ✅ | `graduation` / `recommendation` |
| `category` | ✅ | 枚举 |
| `credits` | ✅ | ≥ 0 |
| `ruleId` | ❌ | 若提供，必须指向已存在的 `college_credit_rules.id`，否则 400 `关联的加分规则不存在` |
| `compLevel` / `awardTier` | ❌ | 填了就在枚举内 |
| `compName`, `obtainedDate`, `certificateRef`, `notes` | ❌ | 自由文本 |
| `verified` | — | **忽略**：服务端强制新建为 `0`（未审核） |
| `id` | — | 忽略（由服务端生成） |

- **成功响应** `200`：落库后重新读取的 DTO
- **失败** `400` 纯文本：`无效的加分类型: ...` / `无效的来源类别: ...` / `分值为空或为负数` / `关联的加分规则不存在` 等

#### `PUT /api/credit-activities/{id}` — 更新记录
- **认证**：已登录（需 CSRF，仅本人）
- **请求体**：同 `POST`
- **语义**：字段级覆盖（`applyDto`），`verified` **不由用户控制**（保留原值）
- **成功响应** `200`：更新后的 DTO
- **失败** `400` 纯文本：`加分记录不存在` / `无权修改此加分记录` / 各项枚举与分值校验

#### `DELETE /api/credit-activities/{id}` — 删除记录
- **认证**：已登录（需 CSRF，仅本人）
- **成功响应** `200`：`{"message":"加分记录已删除"}`
- **失败** `400` 纯文本：`加分记录不存在` / `无权删除此加分记录` / `删除加分记录失败`

---

### 4.10 成长资源库 (Resources)

#### `GET /api/resources` — 资源列表
- **认证**：已登录
- **查询参数**：`type`（可选）∈ `competition` | `course`；不带参数返回全部
- **成功响应** `200`：`Resource[]`（**返回实体全字段**）

```json
[{
  "id": 1, "resourceId": "competition_001", "name": "全国大学生数学建模竞赛",
  "type": "competition", "levelsJson": "[...]", "stagesJson": "[...]",
  "targetMajorsJson": "[...]", "registrationStart": "2026-06-01",
  "registrationDeadline": "2026-09-10", "requiredSkillsJson": "[...]",
  "difficulty": "medium", "preparationPeriod": "3 个月",
  "teamRolesJson": "[...]", "bonusPointJson": "{...}", "provider": "中国工业与应用数学学会",
  "courseLink": null, "description": "...", "teachesSkillsJson": "[...]",
  "sourceUrl": "https://...", "sourceUrlsJson": "[...]", "sourceFile": "...",
  "notesJson": "[...]", "dataQuality": "complete",
  "updatedAt": "2026-09-05", "createdAt": "2026-09-05T00:00:00.000+00:00"
}]
```

- **失败** `400`：纯文本 `type 参数仅支持 competition 或 course`

#### `GET /api/resources/{id}` — 资源详情
- **认证**：已登录
- **路径参数 `id`**：**优先按业务键 `resource_id` 匹配**（如 `competition_001`）；若未命中且是纯数字，回退按物理主键 `id` 查询
- **成功响应** `200`：单个 `Resource`
- **失败** `404`：纯文本 `资源不存在: xxx`

---

### 4.11 RAG 检索切片 (RAG Chunks)

#### `GET /api/rag-chunks` — 切片列表
- **认证**：已登录
- **查询参数**：`relatedResourceId`（可选，关联 `resources.resource_id` 业务键）；不带参数返回全部
- **成功响应** `200`：`RagChunk[]`

```json
[{
  "chunkId": "rag_001", "title": "数学建模竞赛介绍", "text": "...",
  "sourceType": "web", "sourceUrl": "https://...", "sourceFile": null,
  "pageOrSection": "第 2 节", "relatedResourceId": "competition_001",
  "createdAt": "2026-09-05T00:00:00.000+00:00",
  "embeddingJson": "[0.0123,-0.0456, ...]",
  "embeddingModel": "quentinz/bge-small-zh-v1.5:f16",
  "embeddingDimensions": 512,
  "embeddingStatus": "SUCCESS", "embeddingError": null,
  "embeddingUpdatedAt": "2026-09-05T01:00:00.000+00:00",
  "contentHash": "sha256:..."
}]
```

> ⚠️ **注意 `embeddingJson`**：该端点会把**完整向量**返回给任意已登录用户。管理控制台（`/api/admin/rag-chunks`）刻意只返回 `embeddingJsonLength` 而不返回向量，但本端点没有做同样的裁剪。详见 [第 9 节](#9-已知注意事项与风险)。

> 另有内部检索服务 `RagSearchService`（topK 默认 5 / 最大 20，相似度阈值 0.2）供 Agent 的 `search_rag` 工具使用，**未暴露为 HTTP 端点**。

---

### 4.12 能力标签与能力字典

#### `GET /api/capability-tags` — 标准能力标签
- **认证**：已登录
- **成功响应** `200`：`CapabilityTag[]`（按标签名排序）

```json
[{
  "name": "算法与数据结构", "category": "技术能力",
  "level1Desc": "了解基本概念", "level2Desc": "能独立实现", "level3Desc": "能优化与教学",
  "skillAliasesJson": "[\"算法\",\"数据结构\"]",
  "typicalEvidenceJson": "[\"ACM 区域赛铜牌\"]",
  "createdAt": "2026-09-05T00:00:00.000+00:00",
  "updatedAt": "2026-09-05T00:00:00.000+00:00"
}]
```

#### `GET /api/capability-reference` — 能力映射字典
- **认证**：已登录
- **查询参数**：`section`（可选）∈ `tags_to_merge` | `skill_mapping` | `skill_profiles` | `role_profiles` | `major_categories` | `_meta`
- **成功响应** `200`：`CapabilityReference[]`

```json
[{ "id": 1, "section": "skill_mapping", "refKey": "Java", "refValue": "{\"tag\":\"后端开发\"}", "note": null }]
```

- **失败** `400`：纯文本 `section 参数不合法，仅支持: [tags_to_merge, skill_mapping, ...]`

---

### 4.13 管理控制台 (`/api/admin`)

> **全部 23 个端点均为只读 `GET`，仅 `ROLE_ADMIN`。**
> 不提供任意 SQL、任意表名/列名、CSV/JSON 全量导出或数据库文件下载。
> 授权模型见 [9.3](#93-管理控制台信任模型)。

#### 通用查询参数

| 参数 | 适用 | 说明 |
|------|------|------|
| `page` | 全部 | ≥ 1，上限 100000，默认 1 |
| `pageSize` | 全部 | 1–200，默认 50 |
| `sort` | 全部 | **每个数据集独立白名单**，传白名单外的值 → 400 |
| `dir` | 全部 | `asc` / `desc`，缺省用数据集默认方向 |

**未在白名单内的参数一律 `400 {"error":"UNKNOWN_PARAMETER"}`。**

**限流**：每个管理员每分钟 `240` 次查询（`lifecomposer.admin.console-queries-per-minute`），超限 `429 {"error":"ADMIN_RATE_LIMIT"}`。
**超时**：JDBC statement timeout 10 秒。
**审计**：成功 `AUDIT event=admin_query`；被拒 `AUDIT event=admin_query_rejected`（只记 URI 与原因，不记正文）。

**统一错误体**：`{"error":"<CODE>","message":"..."}`
错误码：`INVALID_PARAMETER`、`UNKNOWN_PARAMETER`、`NOT_FOUND`、`ADMIN_RATE_LIMIT`、`ADMIN_QUERY_FAILED`、`FORBIDDEN`。

#### 端点一览

| # | 端点 | 说明 | 可用筛选 |
|---|------|------|----------|
| 1 | `GET /api/admin/dashboard` | 总览 | 仅分页参数（忽略） |
| 2 | `GET /api/admin/users` | 用户与额度 | `q`,`type`,`banned`,`hasProfile` |
| 3 | `GET /api/admin/users/{id}/profile` | 单用户画像详情 | — |
| 4 | `GET /api/admin/user-profiles` | 画像列表 | `q`,`college`,`major`,`grade`,`userId` |
| 5 | `GET /api/admin/planning-history` | AI 规划记录列表 | `userId`,`type`,`status`,`provider`,`model`,`dateFrom`,`dateTo` |
| 6 | `GET /api/admin/planning-history/{id}` | 规划记录详情 | — |
| 7 | `GET /api/admin/chat-messages` | 对话消息列表 | `userId`,`role`,`dateFrom`,`dateTo` |
| 8 | `GET /api/admin/chat-messages/{id}` | 消息详情 | — |
| 9 | `GET /api/admin/feedback` | 反馈列表 | `type`,`resolved`,`userId`,`dateFrom`,`dateTo` |
| 10 | `GET /api/admin/feedback/{id}` | 反馈详情 | — |
| 11 | `GET /api/admin/college-credit-rules` | 加分规则列表 | `college`,`creditType`,`category`,`compLevel`,`awardTier`,`q` |
| 12 | `GET /api/admin/college-credit-rules/{id}` | 规则详情 | — |
| 13 | `GET /api/admin/credit-activities` | 加分记录列表 | `userId`,`creditType`,`verified` |
| 14 | `GET /api/admin/credit-activities/{id}` | 记录详情 | — |
| 15 | `GET /api/admin/resources` | 资源库列表 | `type`,`difficulty`,`dataQuality`,`provider`,`q` |
| 16 | `GET /api/admin/resources/{id}` | 资源详情 | — |
| 17 | `GET /api/admin/rag-chunks` | RAG 切片列表 | `embeddingStatus`,`sourceType`,`relatedResourceId`,`q` |
| 18 | `GET /api/admin/rag-chunks/{chunkId}` | 切片详情 | — |
| 19 | `GET /api/admin/capability-tags` | 能力标签列表 | `category`,`q` |
| 20 | `GET /api/admin/capability-tags/{name}` | 标签详情 | — |
| 21 | `GET /api/admin/capability-reference` | 能力字典列表 | `section`,`q` |
| 22 | `GET /api/admin/capability-reference/{id}` | 字典详情 | — |
| 23 | `GET /api/admin/chat-usage` | 每日聊天计数 | `userId`,`usageDate`,`dateFrom`,`dateTo` |

#### 各数据集的排序白名单（`sort=`）

| 数据集 | 可选 `sort` 值 | 默认 |
|--------|----------------|------|
| users | `id`,`username`,`createdAt`,`type` | `id DESC` |
| user-profiles | `userId`,`updatedAt`,`college`,`grade` | `userId ASC` |
| planning-history | `id`,`createdAt`,`status`,`userId` | `id DESC` |
| chat-messages | `id`,`createTime`,`userId`,`role` | `id DESC` |
| feedback | `id`,`createTime`,`resolved`,`type` | `id DESC` |
| college-credit-rules | `id`,`college`,`credits`,`compName`,`category`,`compLevel` | `id ASC` |
| credit-activities | `id`,`credits`,`obtainedDate`,`verified`,`userId` | `id DESC` |
| resources | `id`,`resourceId`,`name`,`type`,`difficulty`,`dataQuality`,`updatedAt` | `id ASC` |
| rag-chunks | `chunkId`,`title`,`embeddingStatus`,`embeddingUpdatedAt`,`createdAt` | `chunkId ASC` |
| capability-tags | `name`,`category` | `name ASC` |
| capability-reference | `id`,`section`,`refKey` | `id ASC` |
| chat-usage | `id`,`usageDate`,`requestCount`,`userId` | `usageDate DESC` |

#### 各端点的响应字段

**① `GET /api/admin/dashboard`**（无分页，聚合结果）

```json
{
  "tables": { "users": 12, "feedback": 3, "user_profiles": 8, "planning_history": 40,
              "chat_messages": 120, "college_credit_rules": 346, "credit_activities": 5,
              "resources": 18, "rag_chunks": 28, "capability_tags": 68,
              "capability_reference": 30, "chat_usage_daily": 7 },
  "users": { "total": 12, "admins": 1, "banned": 0, "withProfile": 8 },
  "feedback": { "total": 3, "unresolved": 2, "unresolvedSystemErrors": 1 },
  "planning": { "total": 40, "failed": 4 },
  "embeddingStatus": [ { "status": "SUCCESS", "count": 28 } ],
  "recentLlmFailures": [
    { "id": 40, "userId": 3, "type": "QA", "provider": "deepseek",
      "model": "deepseek-flash", "status": "FAILED",
      "errorMessage": "timeout", "createdAt": "2026-09-20T10:00:00.000+00:00" }
  ],
  "generatedAt": "2026-09-20T10:05:00Z",
  "chatUsage": {
    "requests": 33, "activeUsers": 4, "date": "2026-09-20",
    "dailyLimitPerUser": 100, "minuteLimitPerUser": 5,
    "topUsersToday": [ { "userId": 3, "username": "alice", "requestCount": 12 } ]
  }
}
```

**② `GET /api/admin/users`** — items 字段：
`id, username, userType(1|2), role("USER"|"ADMIN"), banned(bool), banEndTime, avatar, createdAt, updatedAt, passwordResetRequired(bool), tempPasswordExpiresAt, hasProfile(bool), usedToday, dailyLimit, remainingToday`

**③ `GET /api/admin/users/{id}/profile`** — ⚠️ **返回完整学号与 `preferencesJson`**：
`userId, username, userType, college, major, grade, studentId(完整), availableTime, skillsJson, interestsJson, experiencesJson, goalsJson, preferencesJson, createdAt, updatedAt`
不存在 → `404 {"error":"NOT_FOUND","message":"用户画像不存在"}`

**④ `GET /api/admin/user-profiles`** — items 字段：
`userId, username, college, major, grade, studentId(掩码), studentIdMasked(true), availableTime, skillsJson, interestsJson, experiencesJson, goalsJson, hasPreferences(bool), createdAt, updatedAt`

> 学号掩码规则：长度 ≤6 时全 `*`；否则保留前 3 位 + `****` + 后 2 位（如 `202****34`）。列表**不含** `preferencesJson`。

**⑤ `GET /api/admin/planning-history`** — items 字段：
`id, userId, username, type, provider, model, status, errorMessage(截断), errorMessageLength, requestLength, requestPreview, responseLength, responsePreview, createdAt`

**⑥ `GET /api/admin/planning-history/{id}`**：
同上，但给完整 `requestJson` / `responseJson` / `errorMessage`（各自截断到 `console-diagnostic-length`，默认 4000 字符）

**⑦ `GET /api/admin/chat-messages`** — items 字段：
`id, userId, username, role(user|assistant|tool), contentLength, contentPreview, structured(bool：是否为工具调用 JSON), createTime`

**⑧ `GET /api/admin/chat-messages/{id}`**：
`id, userId, username, role, content(截断), contentLength, contentTruncated(bool), createTime`

**⑨ `GET /api/admin/feedback`** — items 字段：
`id, userId, username, type(user|system), resolved(bool), resolvedBy, url, contentLength, contentPreview, hasStackTrace, stackTraceLength, userAgentPreview(≤120 字符), createTime, resolvedTime`

**⑩ `GET /api/admin/feedback/{id}`**：
`id, userId, username, type, content, url, userAgent, stackTrace(截断), stackTraceLength, resolved, resolvedBy, createTime, resolvedTime`

**⑪ `GET /api/admin/college-credit-rules`** — items 字段：
`id, college, creditType, category, compLevel, compName, awardTier, credits, categoryCap, teamFormula, studentCohort, docSource, levelsJson, notesPreview, notesLength, createdAt`

**⑫ `GET /api/admin/college-credit-rules/{id}`**：同上但 `notes` 完整

**⑬ `GET /api/admin/credit-activities`** — items 字段：
`id, userId, username, ruleId, creditType, category, compName, compLevel, awardTier, credits, obtainedDate, verified(bool), hasCertificate(bool), notesPreview, notesLength, createdAt`

> **`certificate_ref` 永不返回**，只给布尔 `hasCertificate`。

**⑭ `GET /api/admin/credit-activities/{id}`**：同上但 `notes` 完整

**⑮ `GET /api/admin/resources`** — items 字段：
`id, resourceId, name, type, difficulty, dataQuality, provider, sourceUrl, registrationStart, registrationDeadline, preparationPeriod, levelsJson, targetMajorsJson, updatedAt, descriptionLength, descriptionPreview, createdAt`

**⑯ `GET /api/admin/resources/{id}`**：
`id, resourceId, name, type, difficulty, dataQuality, provider, courseLink, description, sourceUrl, sourceUrlsJson, sourceFile, registrationStart, registrationDeadline, preparationPeriod, levelsJson, stagesJson, targetMajorsJson, requiredSkillsJson, teamRolesJson, bonusPointJson, teachesSkillsJson, notesJson, updatedAt, createdAt`

**⑰ `GET /api/admin/rag-chunks`** — items 字段：
`chunkId, title, sourceType, sourceUrl, sourceFile, pageOrSection, relatedResourceId, createdAt, embeddingModel, embeddingDimensions, embeddingStatus, embeddingError(截断), embeddingErrorLength, embeddingUpdatedAt, contentHash, embeddingJsonLength, textLength, textPreview`

> **`embedding_json` 原始向量永不返回**，只给长度 `embeddingJsonLength`。

**⑱ `GET /api/admin/rag-chunks/{chunkId}`**：
`chunkId, title, text(截断), sourceType, sourceUrl, sourceFile, pageOrSection, relatedResourceId, createdAt, embeddingModel, embeddingDimensions, embeddingStatus, embeddingError, embeddingUpdatedAt, contentHash, embeddingJsonLength, textTruncated(bool)`

**⑲ `GET /api/admin/capability-tags`** — items 字段：
`name, category, level1Desc, level2Desc, level3Desc, skillAliasesLength, skillAliasesPreview, typicalEvidenceLength, typicalEvidencePreview, createdAt, updatedAt`

**⑳ `GET /api/admin/capability-tags/{name}`**：
`name, category, level1Desc, level2Desc, level3Desc, skillAliasesJson, typicalEvidenceJson, createdAt, updatedAt`

**㉑ `GET /api/admin/capability-reference`** — items 字段：
`id, section, refKey, refValueLength, refValuePreview, note`

**㉒ `GET /api/admin/capability-reference/{id}`**：`id, section, refKey, refValue, note`

**㉓ `GET /api/admin/chat-usage`** — items 字段：
`id, userId, username, usageDate, requestCount, updatedAt, dailyLimit`

#### 枚举校验（传错 → `400 INVALID_PARAMETER`）

| 参数 | 允许值 |
|------|--------|
| `type`(users) | `1` / `2` |
| `banned`,`hasProfile`,`resolved`,`verified` | `0`/`1`/`true`/`false` |
| `role` | `user`,`assistant`,`tool` |
| `type`(feedback) | `user`,`system` |
| `creditType` | `graduation`,`recommendation` |
| `category` | `competition`,`lecture`,`course`,`project`,`paper`,`patent`,`sports`,`arts`,`veteran` |
| `compLevel` | `S`,`A+`,`A`,`B+`,`B`,`national`,`provincial`,`school` |
| `awardTier` | `first`,`second`,`third`,`special`,`participation` |
| `type`(resources) | `competition`,`course` |
| `difficulty` | `easy`,`medium`,`hard` |
| `dataQuality` | `complete`,`partial`,`needs_review` |
| `sourceType` | `web`,`pdf`,`json` |
| `embeddingStatus` | `PENDING`,`SUCCESS`,`FAILED`,`SKIPPED` |
| `category`(tags) | `技术能力`,`通用能力` |
| `section` | `tags_to_merge`,`skill_mapping`,`skill_profiles`,`role_profiles`,`major_categories`,`_meta` |
| `dateFrom`/`dateTo`/`usageDate` | `YYYY-MM-DD` |

---

### 4.14 页面路由

| 方法 | 路径 | 认证 | 模板 |
|------|------|------|------|
| GET | `/` | 公开 | `home` |
| GET | `/login` | 公开 | `login` |
| GET | `/admin` | ADMIN | `admin_main`（总览） |
| GET | `/admin/user` | ADMIN | `user_management` |
| GET | `/admin/profile` | ADMIN | `admin_profile` |
| GET | `/admin/planning` | ADMIN | `admin_planning` |
| GET | `/admin/chat` | ADMIN | `admin_chat` |
| GET | `/admin/feedback_management` | ADMIN | `feedback_management` |
| GET | `/admin/credit-rules` | ADMIN | `admin_credit_rules` |
| GET | `/admin/credit-activities` | ADMIN | `admin_credit_activities` |
| GET | `/admin/resources` | ADMIN | `admin_resources` |
| GET | `/admin/rag` | ADMIN | `admin_rag` |
| GET | `/admin/capability-tags` | ADMIN | `admin_capability_tags` |
| GET | `/admin/capability-reference` | ADMIN | `admin_capability_reference` |
| GET | `/admin/usage` | ADMIN | `admin_usage` |
| GET | `/release-notes` | 公开 | `release_notes` |
| GET | `/front/chat_test` | 已登录 | `chat_test`（SSE 过程展示测试页） |
| GET | `/front/change-password` | 已登录 | `change_password`（临时密码会话唯一可用业务页） |

**错误页**：`/error/403`、`/error/404`、`/error/500`、`/error/general`

> `/admin/**` 在过滤器链上即要求 `ROLE_ADMIN`，控制器 `HomeController.adminView()` 再做一次 `isAdmin` 校验，非管理员 302 → `/error/403`。
> 管理页面共用 `external/static/admin.css` + `admin.js`（同一套导航/分页器/筛选栏/详情抽屉），模板只声明 `data-admin-view`。

---

## 5. Agent 内置工具清单

`/api/chat/*` 的模型可调用以下 **5 个只读白名单工具**（不存在任意 SQL / 任意代码执行）。它们**不是 HTTP 端点**，而是通过 SSE 的 `tool_call` / `tool_result` 事件对前端可见。

| 工具名 | 前端展示名 | 参数 | 返回 |
|--------|-----------|------|------|
| `get_user_profile` | 读取当前用户画像 | 无 | `{found, college, major, grade, availableTime, skills, interests, experiences, goals}`；无画像时 `{found:false, message}` |
| `get_user_credit_activities` | 读取当前用户加分记录 | 无 | `{count, items:[{id,creditType,category,compName,compLevel,awardTier,credits,obtainedDate,verified,notes}]}` |
| `query_credit_rules` | 查询加分规则 | `college?`(模糊), `creditType?`, `category?`, `compLevel?`, `awardTier?`, `limit?`(默认 20，最大 50) | `{count, items:[{id,college,creditType,category,compLevel,compName,awardTier,credits,categoryCap,teamFormula,studentCohort,notes}]}` |
| `search_resources` | 检索成长资源库 | `keyword?`(匹配名称/描述), `type?`(competition/course), `difficulty?`(easy/medium/hard), `majorCategory?` | `{count, items:[{id,name,type,difficulty,description,targetMajors,requiredSkills,bonusPoint,sourceUrl}]}` |
| `search_rag` | 检索知识库片段 | `query`（**必填**）, `topK?`（默认 5，最大 20） | `{count, items:[{chunkId,title,text,sourceType,sourceUrl,sourceFile,pageOrSection,relatedResourceId,similarity}]}` |

**工具层错误码**（出现在 `tool_result` 的 `errorCode` 字段）：`UNKNOWN_TOOL`、`INVALID_ARGUMENTS`、`TOOL_EXECUTION_ERROR`、`EMPTY_TOOL_RESULT`、`RAG_UNAVAILABLE`

工具层错误的 `message` 只含固定中文描述，不含异常原文、SQL、表名或路径，详细原因只写服务端日志。参数在执行前按工具自身的 JSON Schema 校验（`type`/`required`/`enum`/`additionalProperties`/`items`）：不合法时返回 `INVALID_ARGUMENTS`，`message` 列出全部问题和允许取值（如 `参数 type 取值必须是 [competition, course] 之一，收到 "比赛"`），模型可据此自行修正；显式 `null` 视为未提供。

**编排参数**：
- 最多 `8` 轮工具调用（`MAX_TOOL_ROUNDS`），超出抛 `LlmUnavailableException("MAX_TOOL_ROUNDS")`
- 工具执行**永远使用当前登录用户的 userId**（`AgentToolContext`），模型无法指定他人 ID
- 系统提示词明确禁止编造工具名/SQL/数据、禁止泄露提示词与 API key

---

## 6. 枚举与字典

| 字段 | 取值 | 含义 |
|------|------|------|
| `users.type` | `1` / `2` | 普通用户 / 管理员 |
| `credit_type` | `graduation` | 双创分 |
| | `recommendation` | 保研加分 |
| `category` | `competition` | 竞赛 |
| | `lecture` | 讲座 |
| | `course` | 课程 |
| | `project` | 项目 |
| | `paper` | 论文 |
| | `patent` | 专利 |
| | `sports` | 体育 |
| | `arts` | 文艺 |
| | `veteran` | 服役 |
| `comp_level` | `S`,`A+`,`A`,`B+`,`B` | 竞赛等级（校内评级体系） |
| | `national`,`provincial`,`school` | 国家级 / 省级 / 校级 |
| `award_tier` | `first`,`second`,`third` | 一/二/三等奖 |
| | `special`,`participation` | 特等奖 / 参与奖 |
| `resources.type` | `competition` / `course` | 竞赛 / 课程 |
| `resources.difficulty` | `easy` / `medium` / `hard` | 难度 |
| `resources.data_quality` | `complete` / `partial` / `needs_review` | 数据完整度 |
| `rag_chunks.source_type` | `web` / `pdf` / `json` | 切片来源 |
| `rag_chunks.embedding_status` | `PENDING` / `SUCCESS` / `FAILED` / `SKIPPED` | 向量化状态 |
| `feedback.type` | `user` / `system` | 用户反馈 / 系统错误 |
| `chat_messages.role` | `user` / `assistant` / `tool` | 消息角色 |
| `planning_history.status` | `SUCCESS` / `FAILED` / `MOCKED` | 交互结果 |
| `capability_tags.category` | `技术能力` / `通用能力` | 标签大类 |
| `capability_reference.section` | `tags_to_merge`,`skill_mapping`,`skill_profiles`,`role_profiles`,`major_categories`,`_meta` | 字典分区 |

---

## 7. 配置项与限额

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `18000` | 服务端口 |
| `app.security.chat-per-minute` | `5` | 每用户每分钟聊天次数 |
| `app.security.chat-per-day` | `100` | 每用户每日聊天次数（Asia/Shanghai） |
| `app.security.chat-max-output-tokens` | `2048` | 服务端强制 `max_tokens` 上限 |
| `app.security.chat-context-messages` | `15` | 发给 LLM 的最近用户/回答消息条数（工具行不计数；<1 按 1） |
| `app.security.chat-stream-max-concurrent` | `32` | 全局同时运行的流式对话上限，无排队 |
| `app.security.sse-timeout-millis` | `300000` | SSE 超时（5 分钟） |
| `app.security.register-per-minute-per-ip` | `10` | 注册限速（每 IP/分钟） |
| `app.security.login-max-failures` | `5` | 登录连续失败锁定阈值 |
| `app.security.login-lock-millis` | `900000` | 锁定 15 分钟 |
| `app.security.admin-intranet-only` | `false` | 管理员 API 是否限内网 |
| `app.security.chat-usage-zone` | `Asia/Shanghai` | 日额度时区 |
| `app.security.chat-usage-retention-days` | `30` | 用量数据保留天数 |
| `lifecomposer.admin.min-password-length` | `12` | 管理员密码最短长度 |
| `lifecomposer.admin.max-password-length` | `72` | 密码最长长度 |
| `lifecomposer.admin.user-min-password-length` | `8` | 普通用户改密最短长度 |
| `lifecomposer.admin.temp-password-ttl-minutes` | `1440` | 临时密码有效期（24 小时） |
| `lifecomposer.admin.console-default-page-size` | `50` | 管理台默认页大小 |
| `lifecomposer.admin.console-max-page-size` | `200` | 管理台最大页大小 |
| `lifecomposer.admin.console-queries-per-minute` | `240` | 管理台每管理员每分钟查询上限 |
| `lifecomposer.admin.console-query-timeout-seconds` | `10` | 管理台 JDBC 超时 |
| `lifecomposer.admin.console-preview-length` | `300` | 列表预览截断长度（最小 40） |
| `lifecomposer.admin.console-diagnostic-length` | `4000` | 详情诊断字段截断长度（最小 200） |
| `rag.defaultTopK` / `rag.maxTopK` / `rag.minSimilarity` | `5` / `20` / `0.2` | RAG 检索默认参数 |
| `embedding.baseUrl` / `embedding.model` | `http://localhost:11434/v1` / `quentinz/bge-small-zh-v1.5:f16` | 本地 Ollama embedding |

LLM 按用途分组配置（`qa` / `planning` / `profile` / `sql` / `chat`），字段：`provider`、`baseUrl`、`model`、`apiKeyEnv`、`enabled`、`timeoutMillis`、`maxRetries`（默认 `2`）、`retryBaseDelayMillis`（默认 `500`）。连接失败或 HTTP 429/500/502/503/504 时在**收到任何响应内容之前**按 `base × 2^n` 退避重试（上限 5 秒，优先采用 `Retry-After`）；读超时和流式输出已开始后不重试。`maxRetries=0` 关闭重试。默认全部指向 DeepSeek `deepseek-flash`；`run.sh` 默认开启 `qa` 与 `chat`。

---

## 8. 错误码总表

### 业务错误码（响应体 `error` 字段）

| 错误码 | HTTP | 出现端点 | 含义 |
|--------|------|----------|------|
| `REGISTER_RATE_LIMIT` | 429 | 注册 | 注册过于频繁 |
| `LOGIN_LOCKED` | 429 | 登录 | 失败次数过多被锁定 |
| `BAD_CREDENTIALS` | 400 | 登录 | 用户名或密码错误 |
| `LOGIN_REJECTED` | 400 | 登录 | 账号被封禁 |
| `TEMP_PASSWORD_EXPIRED` | 403 / 401 | 登录 / 会话守卫 | 临时密码过期 |
| `SESSION_EXPIRED` | 401 | 会话守卫 | 密码变更或管理员角色被授予/撤销导致会话失效（`message` 目前统一为“密码已变更，请重新登录”） |
| `PASSWORD_CHANGE_REQUIRED` | 401 | 会话守卫 | 必须先修改临时密码 |
| `PASSWORD_REJECTED` | 400 | 自助改密 | 当前密码错 / 新密码弱 / 与旧相同 |
| `WEAK_PASSWORD` | 400 | 管理员下发临时密码 | 密码不满足强度策略 |
| `RESET_FAILED` | 400 | 管理员下发临时密码 | 写库失败 |
| `INVALID_INPUT` | 400 | 封禁 | body 缺失或格式非法 |
| `NOT_FOUND` | 404 | 撤销管理员 / 封禁 | 用户不存在 |
| `LAST_ADMIN_PROTECTED` | 409 | 撤销管理员 / 封禁 | 最后一个可登录管理员受保护 |
| `UNAUTHORIZED` | 401 | 自助改密 | 会话缺失 |
| `CHAT_MINUTE_LIMIT` | 429 | chat/send、chat/stream | 分钟额度用尽 |
| `CHAT_DAILY_LIMIT` | 429 | chat/send、chat/stream | 当日额度用尽 |
| `CHAT_IN_PROGRESS` | 409 | chat/send、chat/stream | 同一用户已有 AI 请求在处理，不扣额度 |
| `CHAT_SERVER_BUSY` | 503 | chat/stream | 全局流式并发已满，额度已退还 |
| `LLM_UNAVAILABLE` | 503 / SSE error | chat/* | LLM 不可用（无 fallback）；服务端故障；尚未向用户输出内容时退还日额度（不退分钟令牌，流式已开始输出后不退） |
| `MAX_TOOL_ROUNDS` | 503 / SSE error | chat/* | 工具调用超过 8 轮 |
| `INTERNAL_ERROR` | SSE error | chat/stream | 服务端异常 |
| `ADMIN_RATE_LIMIT` | 429 | /api/admin/* | 管理查询超频 |
| `INVALID_PARAMETER` | 400 | /api/admin/* | 参数值非法 |
| `UNKNOWN_PARAMETER` | 400 | /api/admin/* | 参数不在白名单 |
| `ADMIN_QUERY_FAILED` | 500 | /api/admin/* | 数据库查询失败（不含 SQL/堆栈） |
| `FORBIDDEN` | 403 | /api/admin/* | 权限不足 |

### 通用框架层响应

| 场景 | 状态 | 响应体 |
|------|------|--------|
| Bean Validation 失败 | 400 | `{"字段名":"错误消息", ...}` |
| `ConstraintViolationException` | 400 | 文本 `Invalid input` |
| 路径/查询参数类型不匹配 | 400 | 文本 `Method argument type mismatch` |
| 缺少必填请求参数 | 400 | 文本 `Missing servlet request parameter.` |
| 请求体缺失或非法 JSON | 400 | 文本 `请求体缺失或格式不正确` |
| 不支持的媒体类型 | 400 | 文本 `Unsupported media type` |
| 未知 `/api/**` 路径 | 404 | 空 body |
| 不支持的 HTTP 方法 | 404 | 空 body（API） |
| Bot（空 UA / 爬虫关键词） | 403 | `Forbidden`（优先于所有输入错误） |
| 客户端断开（`ClientAbortException`） | 499 | 文本 `ClientAbortException`（客户端已断开，记录在案） |
| 未捕获异常 | 500 | 文本 `Internal Server Error`（并写入系统反馈） |
| 未登录访问受保护端点 | 403 | Spring Boot 默认错误体 `{timestamp,status,error,path}` |

---

## 9. 已知注意事项与风险

### 9.1 最容易踩的 6 个坑

1. **登录/注册也要 CSRF token**。「公开端点」不等于「免 CSRF」。先 `GET /api/csrf`。
2. **必须带 `User-Agent`**。空 UA 一律 403（Bot 防护），且优先级高于参数校验错误。
3. **未登录是 403，不是 401**。前端拦截器若只判 401 会漏掉未登录态。
4. **`PUT /api/profiles/me` 是全量覆盖**。局部更新前必须先 `GET` 合并，否则未提交字段被写成 `NULL`。
5. **JSON 字段是字符串**。`skillsJson` / `goals` / `targetMajorsJson` 等传输层是 string，需要两次 `JSON.parse/stringify`。
6. **`banTime` 的 `m` 是"月"（30 天），不是"分钟"**。

### 9.2 数据暴露面（建议关注）

- **`GET /api/rag-chunks` 会把完整向量 `embeddingJson` 返回给任意已登录用户**。管理控制台出于同样的考虑**刻意只返回 `embeddingJsonLength`**，说明团队已将该字段视为不应外泄的运维数据，但该用户端点未做同等裁剪。建议：前端不使用该字段，或后端在 `RagChunkController` 层剔除/裁剪 `embeddingJson`。
- `GET /api/resources` 返回 `Resource` 实体全字段（含 `sourceUrlsJson`、`notesJson` 等原始交付数据），无字段级裁剪。
- `GET /api/feedback/all`、`/api/users/all` 返回全量数据（含 `userAgent`、`stackTrace`），仅供 ADMIN。

### 9.3 管理控制台信任模型

管理员被定义为**完全可信的调试/运维角色**（单实例部署）：

- **绝对不返回**：`password_hash`、原始 `embedding_json` 向量、`certificate_ref` 证书路径、服务端环境变量与会话/Cookie。
- **可以看到**：用户画像的个人信息。画像**列表**对学号做掩码（防肩窥），但 `GET /api/admin/users/{id}/profile` **详情返回完整学号与 `preferencesJson`**——因为排查用户规划结果时确实需要。
- 该表述同时写在 `AdminController` 类注释、`API.md` 与 `external/static/admin.js` 文件头，**修改信任模型必须三处同步**。

### 9.4 有状态 / 单实例假设

- **限流与锁定是内存态**（`InMemoryMinuteRateLimiter`、`LoginAttemptService`、`RegistrationRateLimiter`、`AdminQueryRateLimiter`）：多实例部署时各实例独立计数，限额会被放大。
- **日额度是数据库态**（`chat_usage_daily`），跨实例一致。
- 会话是**服务端 Session**，需要粘性会话或共享 Session 存储才能横向扩展。

### 9.5 其他行为差异

- **QA 有 mock fallback，Chat 没有**：`/api/qa/ask` 在 LLM 失败时返回 `mocked:true` + `[Mock]` 前缀答案（`provider:"mock"`，历史 `status=FAILED`）；`/api/chat/*` 遇同样情况直接 `503 LLM_UNAVAILABLE` 或 SSE `error`。
- **`timestamp` 类型不统一**：`QaResponse.timestamp` 是 epoch 毫秒数字，`ChatResponse.createTime` 是本地日期时间字符串，实体 `createdAt` 是 ISO-8601。
- **`DELETE /api/chat/context` 是物理删除**，且会改变后续 Agent 的上下文。
- **`/api/users/current` 用 `isBanned`，`/api/users/all` 用 `banned`**，字段名不一致。
- **`GET /api/planning/history/{id}` 跨用户返回 400 而非 403**（业务层判定），与过滤器层的 403 语义不同。
- 注册密码策略宽松（1–50 字符），是**为兼容既有客户端刻意保留**的（见 `AdminPasswordPolicy` 类注释）。

---

## 附：端点总数核对

| 模块 | 端点数 |
|------|--------|
| CSRF | 1 |
| 用户管理 | 12 |
| 反馈 | 5 |
| 用户画像 | 2 |
| 规划历史 | 2 |
| 问答 QA | 2 |
| 对话 Chat | 4 |
| 加分规则 | 3 |
| 加分记录 | 5 |
| 成长资源库 | 2 |
| RAG 切片 | 1 |
| 能力标签 | 1 |
| 能力字典 | 1 |
| 管理控制台 | 23 |
| **合计** | **64** |

> 本指南由源码逐端点核对生成。若 Controller 变更，请同步更新本文件与 `API.md`。
