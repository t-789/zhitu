# 数据库架构设计（Schema Design）

## 概述

本系统面向北邮大学生成长规划场景，基于 SQLite 数据库存储。当前设计包含 17 张表，全部已实现；v0.0.4 为 `rag_chunks` 增加 embedding 字段，v0.0.5 增加聊天用量表，v0.1.5 增加登录失败计数与登录审计两张表：

| 表名 | 用途 |
|------|------|
| `users` | 用户账户 |
| `feedback` | 用户反馈与系统错误 |
| `user_profiles` | 用户画像扩展（目标并入 `goals` 列） |
| `planning_history` | AI 交互历史记录 |
| `chat_messages` | 对话消息（支持 user / assistant / tool 角色） |
| `college_credit_rules` | 加分规则标准（双创分 + 保研加分） |
| `credit_activities` | 用户已获得的加分记录 |
| `resources` | 成长资源库（竞赛 + 课程，靠 `type` 区分） |
| `rag_chunks` | RAG 检索切片（v0.0.4 增加 embedding_json / embedding_model / embedding_dimensions / embedding_status / embedding_error / embedding_updated_at / content_hash） |
| `capability_tags` | 标准能力标签字典（10 个标签 × L1/L2/L3） |
| `capability_reference` | 能力映射/模板/大类字典（tags_to_merge / skill_mapping / skill_profiles / role_profiles / major_categories / _meta） |
| `chat_usage_daily` | v0.0.5 聊天每日用量与额度计数（user_id + usage_date 唯一） |
| `profile_change_candidates` | v0.1 M2 聊天提议的画像变更候选（待确认/已确认/已拒绝/过期/冲突） |
| `user_capability_states` | v0.1 M4 用户能力最小状态（等级/证据/来源/置信度） |
| `recommendation_feedback` | v0.1 M6 推荐反馈（方向、类型、评分配置版本、快照） |
| `login_attempt` | v0.1.5 登录失败计数（v0.1.9 起分 `pair` / `account` 两层） |
| `login_log` | v0.1.5 登录审计（成功/失败、IP、UA、失败原因） |

> **注意**：SQLite 当前 `PRAGMA foreign_keys = OFF`（默认），表之间的 `REFERENCES` 约束仅作为逻辑关联标注，运行时不强制。

---

## 关系总览（逻辑关联）

```
users
  ├─ 1:1 ── user_profiles     （按 user_id 关联）
  ├─ 1:N ── feedback          （按 user_id 关联）
  ├─ 1:N ── planning_history  （按 user_id 关联）
  └─ 1:N ── chat_messages     （按 user_id 关联）

college_credit_rules
  └─ 1:N ── credit_activities （按 rule_id 可选关联）

resources
  └─ 1:N ── rag_chunks          （按 related_resource_id 可选关联）

capability_tags
  └─ 被引用 ── capability_reference / resources.teaches_skills_json / rag_chunks （按标签名弱关联）
```

---

## 各表详细设计

### 1. `users`（用户表）✅ 已实现

存储用户账户信息。

```sql
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL,
  student_id TEXT NULL,                          -- v0.1.3：学号，账号身份权威；旧账号/管理员允许 NULL
  email TEXT NULL,                               -- v0.1.1：旧用户允许 NULL，大小写不敏感唯一
  real_name TEXT NULL,                           -- v0.1.1：新注册页填写的姓名
  password_hash TEXT NOT NULL,
  type INTEGER NOT NULL DEFAULT 1 CHECK(type IN (1,2)),
  is_banned BOOLEAN NOT NULL DEFAULT 0,
  ban_end_time TIMESTAMP NULL,
  avatar TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  password_reset_required BOOLEAN NOT NULL DEFAULT 0,   -- v0.0.6：临时密码待修改
  temp_password_expires_at TIMESTAMP NULL,              -- v0.0.6：临时密码有效期
  password_changed_at TIMESTAMP NULL,                   -- v0.0.6：最近一次设置密码时间
  credential_version INTEGER NOT NULL DEFAULT 1         -- v0.0.6：会话失效用的密码代号
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_ci ON users(lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_student_id ON users(student_id) WHERE student_id IS NOT NULL;
```

**字段说明：**

| 字段 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `id` | 自动 | 主键 | 1 |
| `username` | 是 | 用户名，唯一 | 'alice' |
| `student_id` | 否 | v0.1.3：学号，唯一；`/api/auth/register` 写入且 `username=student_id`。登录可用学号/用户名/邮箱，三者共用一个命名空间（注册时互相查重） | '20260001' |
| `email` | 否 | 注册邮箱，存储时归一为小写；非空值大小写不敏感唯一。旧用户为 NULL，未做邮箱验证 | 'alice@example.edu' |
| `real_name` | 否 | 注册页姓名；旧用户为 NULL | '张三' |
| `password_hash` | 是 | BCrypt 哈希密码 | `$2a$10$...` |
| `type` | 是 | 用户类型：1=USER, 2=ADMIN | 1 |
| `is_banned` | 是 | 是否封禁（0=正常，1=封禁） | 0 |
| `ban_end_time` | 否 | 封禁结束时间（毫秒时间戳），null 表示永久或未封禁 | 1719500000000 |
| `avatar` | 否 | 头像图片路径或 URL | null |
| `created_at` | 自动 | 创建时间 | 2026-06-27 10:00:00 |
| `updated_at` | 自动 | 更新时间 | 2026-06-27 10:00:00 |

---

### 2. `feedback`（反馈表）✅ 已实现

存储用户提交反馈和系统自动收集的错误报告。

```sql
CREATE TABLE IF NOT EXISTS feedback (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NULL,
  username TEXT NULL,
  content TEXT NOT NULL,
  type TEXT NOT NULL,
  url TEXT NULL,
  user_agent TEXT NULL,
  stack_trace TEXT NULL,
  create_time TIMESTAMP NOT NULL,
  resolved BOOLEAN NOT NULL DEFAULT 0,
  resolved_by TEXT NULL,
  resolved_time TIMESTAMP NULL
);
```

**字段说明：**

| 字段 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `id` | 自动 | 主键 | 1 |
| `user_id` | 否 | 关联用户（未登录时可为 NULL） | 5 |
| `username` | 否 | 冗余存储用户名（提交时的快照） | 'alice' |
| `content` | 是 | 反馈内容/错误信息 | '页面加载失败' |
| `type` | 是 | 类型：'user'=用户反馈，'system'=系统错误 | 'system' |
| `url` | 否 | 触发错误的 URL（系统错误时自动记录） | '/api/profiles/me' |
| `user_agent` | 否 | 请求 User-Agent（用于 Bot 检测与问题排查） | 'Mozilla/5.0...' |
| `stack_trace` | 否 | 错误堆栈信息（仅系统错误） | 'java.lang.NullPointerException...' |
| `create_time` | 是 | 创建时间（代码传入，非自动填充） | 2026-06-27 10:00:00 |
| `resolved` | 是 | 是否已解决（BOOLEAN, 0=未解决，1=已解决） | 0 |
| `resolved_by` | 否 | 处理人（管理员用户名） | 'admin' |
| `resolved_time` | 否 | 解决时间 | 2026-06-28 14:00:00 |

---

### 3. `user_profiles`（用户画像表）✅ 已实现

与 `users` 表 1:1 逻辑关联，存储用户画像扩展信息。JSON 字段应对灵活扩展需求。

```sql
CREATE TABLE IF NOT EXISTS user_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL UNIQUE,
    college TEXT,
    major TEXT,
    grade TEXT,
    student_id TEXT,
    skills_json TEXT,
    interests_json TEXT,
    experiences_json TEXT,
    preferences_json TEXT,
    available_time TEXT,
    goals TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

**字段说明：**

| 字段 | 说明 | 示例 |
|------|------|------|
| `user_id` | 关联用户，`UNIQUE` 约束保证 1:1 | 1 |
| `college` | 学院 | '计算机学院' |
| `major` | 专业 | '软件工程' |
| `grade` | 年级（文本存储，如"大一"或入学年份） | '大一' 或 '2023' |
| `student_id` | 学号 | '2023210123' |
| `skills_json` | 技能列表（JSON 数组） | `["Python","Java"]` |
| `interests_json` | 兴趣方向（JSON 数组） | `["人工智能","嵌入式"]` |
| `experiences_json` | 经历记录（JSON 数组） | `["电子设计竞赛"]` |
| `preferences_json` | 用户偏好（JSON 对象） | `{"theme":"dark"}` |
| `available_time` | 每周可投入时间（自由文本，对应数据样例 `available_time`） | '6 hours/week' |
| `goals` | 用户成长目标（JSON 数组字符串）。2026-09-05 起原独立 `goals` 表已移除，目标方向统一由本列承载 | `["了解竞赛","积累项目经历"]` |
| `version` | v0.1 新增，单调递增乐观锁版本（默认 0；任何一次写入 +1） | 1 |
| `created_at` | 创建时间（自动，datetime 函数） | 2026-06-27 10:00:00 |
| `updated_at` | 更新时间（自动，datetime 函数） | 2026-06-27 10:00:00 |

> **2026-09-05 变更**：① 为兼容 `样例/data/student_profiles.json` 新增 `available_time`、`goals` 两列（样例中的 `_说明` 为注释键，不入库）；代码落地时在 `DatabaseInitializer.migrateUserSchema()` 中按列检测并 ALTER TABLE ADD COLUMN。② **原 `goals` 表（含 `/api/goals` 整套 CRUD）已决定移除**：其能力与 `user_profiles.goals` 列冗余。代码落地时需同步删除 GoalsController/Service/Repository 及相关测试，并将存量 data.db 中的 goals 行合并进对应 profile 后再停用表。

---

### 4. `planning_history`（AI 交互历史表）✅ 已实现

存储用户与 AI 的交互历史，包括问答、规划、画像分析等类型。

```sql
CREATE TABLE IF NOT EXISTS planning_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    type TEXT NOT NULL,
    request_json TEXT,
    response_json TEXT,
    provider TEXT,
    model TEXT,
    status TEXT NOT NULL DEFAULT 'MOCKED',
    error_message TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

**字段说明：**

| 字段 | 说明 | 示例 |
|------|------|------|
| `user_id` | 关联用户 | 1 |
| `type` | 交互类型（自由文本，常见值见下表） | 'QA' |
| `request_json` | 请求内容（JSON 字符串，可为空） | `{"question":"适合参加什么竞赛"}` |
| `response_json` | 响应内容（JSON 字符串，可为空） | `{"answer":"建议参加..."}` |
| `provider` | LLM 提供商 | 'deepseek', 'ollama' |
| `model` | 使用的模型名称 | 'lfm2.5:8b' |
| `status` | 执行状态 | 'MOCKED'（未启用LLM时默认）、'SUCCESS'、'FAILED' |
| `error_message` | 失败时的错误信息 | null 或 'Timeout' |
| `created_at` | 创建时间（自动，datetime 函数） | 2026-06-27 10:00:00 |

> **`type` 取值说明**：代码无 CHECK 约束，常见约定值为 `'QA'`（问答）、`'PLAN'`（规划）、`'PROFILE_ANALYSIS'`（画像分析）。

---

### 5. `chat_messages`（对话消息表）✅ 已实现

对话消息表，存储用户与 AI 助手的对话内容。用户重新登录后自动恢复历史。

```sql
CREATE TABLE IF NOT EXISTS chat_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  prompt_version TEXT,
  create_time TIMESTAMP NOT NULL
);
```

| 字段 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `id` | auto | Primary key | 1 |
| `user_id` | 是 | 所属用户 ID | 5 |
| `prompt_version` | v0.1 M3：assistant 行使用的提示词版本指纹（如 `CHAT_SYSTEM=4;PROFILE_EXTRACTION=2`），user/tool 行为 NULL；旧库由 `ChatMessageRepository.migrateSchema()` 幂等补列 |
| `role` | 是 | 'user' 用户消息；'assistant' AI 回复或工具调用请求；'tool' 工具执行结果 | 'user' |
| `content` | 是 | 消息内容。普通文本直接存储；tool-call 场景为结构化 JSON 字符串（见下方 v0.0.4 说明） | '你好，请问有什么可以帮助你的？' |
| `create_time` | 是 | 创建时间 | 2026-06-28 12:00:00 |

> **v0.0.4 tool 消息约定**：一次工具调用保存两条记录。assistant 记录形如
> `{"type":"tool_call","turnId":"turn-1","name":"search_resources","callId":"call-1","arguments":{"keyword":"数学建模"}}`；
> tool 记录形如 `{"type":"tool_result","turnId":"turn-1","callId":"call-1","ok":true,"data":{...}}`。
> 读取历史拼装下一轮 LLM 上下文时，按 `turnId` 分组：同一轮多个 tool_call 合并为一条带 `tool_calls` 的 assistant 消息，
> 不同轮次保持独立；tool 记录还原为带 `tool_call_id` 的 tool 消息。旧数据无 `turnId` 时回退为连续记录分组。
> thinking 过程、计时状态和未完成文本不持久化。

---

### 6. `college_credit_rules`（加分规则表）📋 待建

记录双创分和保研加分的所有标准规则。双创分和保研加分共用此表，通过 `credit_type` 区分。

```sql
CREATE TABLE college_credit_rules (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  college           TEXT NOT NULL,
  credit_type       TEXT NOT NULL CHECK(credit_type IN ('graduation', 'recommendation')),
  category          TEXT NOT NULL CHECK(category IN ('competition', 'lecture', 'course', 'project', 'paper', 'patent', 'sports', 'arts', 'veteran')),
  comp_level        TEXT CHECK(comp_level IN ('S', 'A+', 'A', 'B+', 'B', 'national', 'provincial', 'school')),
  comp_name         TEXT,
  award_tier        TEXT CHECK(award_tier IN ('first', 'second', 'third', 'special', 'participation')),
  credits           REAL NOT NULL,
  category_cap      REAL,
  team_formula      TEXT,
  student_cohort    TEXT,
  doc_source        TEXT,
  levels_json       TEXT,
  notes             TEXT,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**字段说明：**

| 字段 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `id` | 自动 | 主键 | 1 |
| `college` | 是 | 学院名称 | '信通院', '计算机学院' |
| `credit_type` | 是 | 加分类型: `graduation`=双创分, `recommendation`=保研加分 | 'recommendation' |
| `category` | 是 | 来源类别 | 'competition', 'lecture'(仅双创分), 'course'(仅双创分) |
| `comp_level` | 否 | 竞赛级别（非竞赛类 NULL） | 'S', 'A+', 'B', 'national' |
| `comp_name` | 否 | 竞赛/活动具体名称 | '全国大学生电子设计竞赛' |
| `award_tier` | 否 | 奖项等级（非竞赛类 NULL） | 'first', 'second', 'third' |
| `credits` | 是 | 对应分值 | 2.5, 0.2 |
| `category_cap` | 否 | 该类别的总学分上限 | 2.0（双创课上限） |
| `team_formula` | 否 | 团队赛加分公式说明 | '系数 = 1 + (x-1) × 0.6' |
| `student_cohort` | 否 | 适用年级/学生群体 | '2023' |
| `doc_source` | 否 | 来源文件名称 | '信通院创新实践活动加分办法_2023级起适用.pdf' |
| `levels_json` | 否 | 竞赛阶段数组（JSON），由 comp_level/竞赛名称推断；不确定时填空数组。取值不封闭（另有 regional / world_final 等），不设 CHECK | `["national"]` |
| `notes` | 否 | 人工复核备注（对应样例 `_notes`，346 条中 228 条非空；含分档口径、个人累计上限等说明） | '特等奖按一等奖认定；个人累计加分上限4分。' |
| `created_at` | 自动 | 记录创建时间 | 2026-06-11 12:00:00 |

> **样例校验（2026-09-05）**：`样例/extracted/*.json` 共 346 条规则，已全量验证 CHECK / NOT NULL 零违反，可按列名直接导入。注意样例 `credit_type` 目前全部为 `recommendation`（保研加分），`graduation`（双创分）数据尚缺，待数据负责人补充。

---

### 7. `credit_activities`（用户加分记录表）📋 待建

记录用户已获得的各类加分经历。`rule_id` 可选关联规则表，便于先记录后匹配。

```sql
CREATE TABLE credit_activities (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id           INTEGER NOT NULL,
  rule_id           INTEGER,
  credit_type       TEXT NOT NULL CHECK(credit_type IN ('graduation', 'recommendation')),
  category          TEXT NOT NULL,
  comp_name         TEXT,
  comp_level        TEXT,
  award_tier        TEXT,
  credits           REAL NOT NULL,
  obtained_date     TEXT,
  certificate_ref   TEXT,
  verified          INTEGER DEFAULT 0,
  notes             TEXT,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ca_user ON credit_activities(user_id);
CREATE INDEX idx_ca_type ON credit_activities(credit_type);
```

**字段说明：**

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 自动 | 主键 |
| `user_id` | 是 | 关联 `users(id)` |
| `rule_id` | 否 | 关联 `college_credit_rules(id)`，可先录后关联 |
| `credit_type` | 是 | 冗余字段，直接从规则继承或手动填写 |
| `category` | 是 | 冗余字段 |
| `comp_name` | 否 | 竞赛/活动名称 |
| `comp_level` | 否 | 竞赛级别 |
| `award_tier` | 否 | 奖项 |
| `credits` | 是 | 实际获得的加分值 |
| `obtained_date` | 否 | 获奖/完成日期 |
| `certificate_ref` | 否 | 证书或证明文件路径 |
| `verified` | 否 | 审核状态: 0=未审核, 1=已审核 |
| `notes` | 否 | 备注 |

> **冗余设计说明**：`credit_type`、`category`、`comp_level`、`award_tier` 在活动表中冗余存储，确保规则表内容变更时不会影响用户已有记录。

---

### 8. `resources`（成长资源表）📋 待建

成长资源库：竞赛与课程同表存储，靠 `type` 区分。种子数据：`样例/data/resources.json`（18 条：竞赛 12 + 课程 6）。嵌套结构以 JSON TEXT 列原样保留（与 `user_profiles` 的 JSON 风格一致），不对 stages / bonus_point 做垂直分表。

```sql
CREATE TABLE resources (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  resource_id           TEXT NOT NULL UNIQUE,
  name                  TEXT NOT NULL,
  type                  TEXT NOT NULL CHECK(type IN ('competition', 'course')),
  levels_json           TEXT,
  stages_json           TEXT,
  target_majors_json    TEXT,
  registration_start    TEXT,
  registration_deadline TEXT,
  required_skills_json  TEXT,
  difficulty            TEXT CHECK(difficulty IN ('easy', 'medium', 'hard')),
  preparation_period    TEXT,
  team_roles_json       TEXT,
  bonus_point_json      TEXT,
  provider              TEXT,
  course_link           TEXT,
  description           TEXT,
  teaches_skills_json   TEXT,
  source_url            TEXT,
  source_urls_json      TEXT,
  source_file           TEXT,
  notes_json            TEXT,
  data_quality          TEXT NOT NULL DEFAULT 'needs_review' CHECK(data_quality IN ('complete', 'partial', 'needs_review')),
  updated_at            TEXT,
  created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

> **双键设计**：`id`（INTEGER AUTOINCREMENT）为物理主键，与项目其余表风格一致；`resource_id` 为数据侧业务 id（`competition_###` / `course_###`），UNIQUE，供 `rag_chunks.related_resource_id` 及样例数据文件间互相引用。导入时按 `resource_id` 做 upsert（重复导入同一交付包不产生重复行）。

**字段说明：**

| 字段 | 适用 | 说明 | 示例 |
|------|------|------|------|
| `id` | 全部 | 物理主键（AUTOINCREMENT） | 1 |
| `resource_id` | 全部 | 数据侧业务 id（UNIQUE），供外部引用关联 | 'competition_002' |
| `name` | 全部 | 资源名称 | '蓝桥杯全国软件和信息技术专业人才大赛' |
| `type` | 全部 | competition=竞赛, course=课程 | 'competition' |
| `levels_json` | 竞赛 | 阶段数组（JSON），取值不封闭，不设 CHECK；空数组=未确认 | `["provincial","national"]` |
| `stages_json` | 竞赛 | 赛程安排数组：`[{"stage","month","format","award_ratio"}]` | `[{"stage":"provincial","month":"4月",...}]` |
| `target_majors_json` | 全部 | 目标专业：`{"categories":[学科大类],"majors":[...]}`；不限时为 `["不限"]` | `{"categories":["工科"]}` |
| `registration_start` | 竞赛 | 报名开始日（ISO 日期），未确认填 NULL（`notes_json` 记录 unknown 说明） | '2026-05-01' |
| `registration_deadline` | 竞赛 | 报名截止日 | '2026-09-07' |
| `required_skills_json` | 竞赛 | 技能需求**引用**：`{"profile":模板名}`，可带 `extra`/`exclude`；使用前必须经 `capability_reference` 的 skill_profiles 展开。课程为空数组 | `{"profile":"algorithm_contest"}` |
| `difficulty` | 全部 | 难度三档 | 'medium' |
| `preparation_period` | 全部 | 建议准备周期（自由文本） | '1-3 months' |
| `team_roles_json` | 竞赛 | 队伍角色引用：`{"profile":...}` 或数组；NULL/空=个人赛 | `{"profile":"modeling_team"}` |
| `bonus_point_json` | 竞赛 | 保研加分档位：`{"档位中文描述": 分值}`，含 `_note` 记录依据文件 | `{"国家级一等奖及以上":3.0}` |
| `provider` | 课程 | 开课单位 | '浙江大学' |
| `course_link` | 课程 | 课程链接 | 'https://www.icourse163.org/...' |
| `description` | 课程 | 课程介绍（竞赛条目为 NULL） | '翁恺主讲...' |
| `teaches_skills_json` | 课程 | 学完培养的标准能力标签数组（直接取值 `capability_tags.name`） | `["编程基础"]` |
| `source_url` | 全部 | 主来源链接 | 'https://www.mcm.edu.cn' |
| `source_urls_json` | 全部 | 多来源链接对象：`{"intro":..,"registration":..}` 等 | `{"intro":"https://..."}` |
| `source_file` | 全部 | 来源文件名（网络来源为空串） | '' |
| `notes_json` | 全部 | 样例 `_notes` 数组（缺失值记录、复核线索、unknown 说明） | `["报名日期未确认"]` |
| `data_quality` | 全部 | 数据质量：complete=双重确认 / partial=可选字段未确认 / needs_review=二手来源待核成。默认 needs_review（保守兜底） | 'complete' |
| `updated_at` | 全部 | 数据侧维护日期 | '2026-09-04' |
| `created_at` | 全部 | 入库时间（自动） | 2026-09-05 10:00:00 |

---

### 9. `rag_chunks`（RAG 切片表）✅ 已实现（v0.0.4 增加 embedding 字段）

检索增强用的知识切片。种子数据：`样例/rag/chunks.json`（28 条：学院加分规则概览 ×6、竞赛 ×12、课程 ×6、能力标签说明 ×2、早期手写 ×2）。

```sql
CREATE TABLE rag_chunks (
  chunk_id              TEXT PRIMARY KEY,
  title                 TEXT NOT NULL,
  text                  TEXT NOT NULL,
  source_type           TEXT CHECK(source_type IN ('web', 'pdf', 'json')),
  source_url            TEXT,
  source_file           TEXT,
  page_or_section       TEXT,
  related_resource_id   TEXT,
  created_at            TEXT,
  embedding_json        TEXT,
  embedding_model       TEXT,
  embedding_dimensions  INTEGER,
  embedding_status      TEXT,
  embedding_error       TEXT,
  embedding_updated_at  TEXT,
  content_hash          TEXT
);

CREATE INDEX idx_rag_related ON rag_chunks(related_resource_id);
CREATE INDEX idx_rag_embedding_status ON rag_chunks(embedding_status);
```

存量库由 `RagChunkRepository.createTableIfNeeded()/migrateSchema()` 通过 `PRAGMA table_info` 逐列检测并
`ALTER TABLE ADD COLUMN` 幂等补齐上述 7 个 v0.0.4 字段。

| 字段 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `chunk_id` | 是 | 切片唯一 id | 'rag_001' |
| `title` | 是 | 切片标题 | '数学建模竞赛参赛基本要求' |
| `text` | 是 | 切片正文（程序化生成，禁止手写编造） | '全国大学生数学建模竞赛…' |
| `source_type` | 否 | 来源类型（样例分布：web 20 / pdf 6 / json 2） | 'web' |
| `source_url` | 否 | 来源链接（可回查） | 'https://www.mcm.edu.cn' |
| `source_file` | 否 | 来源文件名 | '' |
| `page_or_section` | 否 | 页码/章节定位 | '参赛须知' |
| `related_resource_id` | 否 | 逻辑关联 `resources.resource_id`（业务 id）；样例中 8/28 条不关联任何资源（如学院规则概览），允许 NULL | 'competition_001' |
| `created_at` | 否 | 切片生成日期 | '2026-06-06' |
| `embedding_json` | 否 | 向量 JSON 数组；NULL 表示尚未生成，不参与检索 | '[0.12,-0.03,...]' |
| `embedding_model` | 否 | 生成向量的模型 | 'quentinz/bge-small-zh-v1.5:f16' |
| `embedding_dimensions` | 否 | 向量维度（从 embeddings 响应数组长度获取，不硬编码） | 768 |
| `embedding_status` | 否 | PENDING / SUCCESS / FAILED / SKIPPED | 'SUCCESS' |
| `embedding_error` | 否 | 失败原因（截断，不含密钥） | 'HTTP 500 ...' |
| `embedding_updated_at` | 否 | 最近一次 embedding 更新时间（ISO-8601） | '2026-09-13T10:56:40Z' |
| `content_hash` | 否 | SHA-256(text)，用于判断是否重新生成 embedding | 'a1b2...' |

> **`rag/test_questions.json` 不入库**：10 个检索回归测试题（含 `expected_chunk_ids`）属于测试夹具，建议放在 `src/test/resources/` 下作为检索效果的验收数据，不建表。

---

### 10. `capability_tags`（能力标签表）📋 待建

标准能力标签权威字典（唯一来源：`样例/draft/capability-tags.json` 的 `tags` 节）。推荐模块解释「差在哪、怎么补」时使用。

```sql
CREATE TABLE capability_tags (
  name                  TEXT PRIMARY KEY,
  category              TEXT NOT NULL CHECK(category IN ('技术能力', '通用能力')),
  level1_desc           TEXT,
  level2_desc           TEXT,
  level3_desc           TEXT,
  skill_aliases_json    TEXT,
  typical_evidence_json TEXT,
  created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

| 字段 | 说明 | 示例 |
|------|------|------|
| `name` | 标准标签名（样例 10 个：编程基础、算法与数据结构、数学建模、数据分析、AI 工具应用、文档写作、表达展示、团队协作、项目经验、英语阅读） | '编程基础' |
| `category` | 技术能力 / 通用能力 | '技术能力' |
| `level1_desc` ~ `level3_desc` | L1/L2/L3 等级描述 | '能用一门语言完成课程作业…' |
| `skill_aliases_json` | 常见写法数组 | `["C语言","C++",...]` |
| `typical_evidence_json` | 典型证据数组 | `["课程成绩单",...]` |

---

### 11. `capability_reference`（能力映射字典表）📋 待建

承载 capability-tags.json 中除 `tags` 外的其余节：`tags_to_merge`、`skill_mapping`、`skill_profiles`、`role_profiles`、`major_categories`，外加 `_meta` 行记录字典版本号。统一为 key→JSON 的窄表，避免为一类低频变化的字典拆 5 张表。

```sql
CREATE TABLE capability_reference (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  section   TEXT NOT NULL CHECK(section IN ('tags_to_merge', 'skill_mapping', 'skill_profiles', 'role_profiles', 'major_categories', '_meta')),
  ref_key   TEXT NOT NULL,
  ref_value TEXT NOT NULL,
  note      TEXT,
  UNIQUE(section, ref_key)
);
```

| 字段 | 说明 | 示例 |
|------|------|------|
| `section` | 所属字典节 | 'skill_mapping' |
| `ref_key` | 键 | 'Python编程' / 'algorithm_contest' / '工科' |
| `ref_value` | 值：标量文本或 JSON 数组/对象。skill_mapping=标签数组；skill_profiles=`{"skills":[...],"note":...}`；role_profiles=角色数组；major_categories=专业数组；tags_to_merge=合并去向文本 | `["编程基础"]` |
| `note` | 该节 `_说明` 中提取的备注（可空） | '可复用的技能需求模板' |

**用法约定（数据侧规范）**：
- 学生自填技能 → 先过 `skill_mapping` 归一，再与标签匹配，匹配前先展开资源引用模板。
- `resources.required_skills_json.profile` → 查 `section='skill_profiles'` 行展开为标签列表。
- `target_majors_json.categories` → 查 `section='major_categories'` 判断专业归属。
- 字典文件版本记入 `section='_meta'`（如 ref_key='version', ref_value='1.1'）。

---

### 12. `chat_usage_daily`（聊天每日用量表）✅ v0.0.5 新增

Milestone 5 的聊天配额持久层。分钟级限流在单机内存中完成，日额度必须落库并使用原子 upsert，防止并发超额。

```sql
CREATE TABLE IF NOT EXISTS chat_usage_daily (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL,
  usage_date    TEXT NOT NULL,              -- Asia/Shanghai 自然日，格式 YYYY-MM-DD
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, usage_date)
);

CREATE INDEX IF NOT EXISTS idx_chat_usage_date ON chat_usage_daily(usage_date);
```

| 字段 | 说明 | 示例 |
|------|------|------|
| `user_id` | 用户 ID | 5 |
| `usage_date` | 自然日（Asia/Shanghai） | '2026-09-13' |
| `request_count` | 当日已准入的聊天请求数 | 12 |
| `updated_at` | 最近一次计数/重置时间 | 2026-09-13 22:00:00 |

**并发控制**：`ChatUsageRepository.tryIncrement()` 使用 SQLite upsert：
```sql
INSERT INTO chat_usage_daily(user_id, usage_date, request_count, updated_at)
VALUES (?, ?, 1, CURRENT_TIMESTAMP)
ON CONFLICT(user_id, usage_date) DO UPDATE SET
  request_count = request_count + 1,
  updated_at = CURRENT_TIMESTAMP
WHERE request_count < ?;
```
返回 1 表示准入成功，0 表示已达日上限；被拒绝的请求不增加计数。管理员重置只允许当前自然日。

---

## v0.1 新增表（M2/M4/M6）

### `profile_change_candidates`（画像变更候选）

```sql
CREATE TABLE IF NOT EXISTS profile_change_candidates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    candidate_id TEXT NOT NULL UNIQUE,
    user_id INTEGER NOT NULL,
    field_name TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT NOT NULL,
    rationale TEXT,
    source TEXT NOT NULL DEFAULT 'chat',
    status TEXT NOT NULL DEFAULT 'PENDING_CONFIRMATION'
        CHECK(status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'CONFLICT')),
    base_version INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT NOT NULL,
    decided_at TEXT,
    decision_reason TEXT,
    merged_version INTEGER
);
```

- 聊天工具只创建 `PENDING_CONFIRMATION` 行；`POST /api/profiles/change-candidates/{id}/decision` 在同一事务内校验 `base_version` 与当前画像版本，确认后以 `WHERE user_id=? AND version=?` 合并写入。
- 过期、重复确认、并发更新分别落到 `EXPIRED` / 原状态幂等返回 / `CONFLICT`，均不会污染正式画像。
- `decision_reason` 只记录用户拒绝理由，不进入画像。

### `user_capability_states`（用户能力状态）

```sql
CREATE TABLE IF NOT EXISTS user_capability_states (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    tag_name TEXT NOT NULL,
    level TEXT,
    evidence_json TEXT,
    source TEXT NOT NULL,
    confidence REAL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(user_id, tag_name)
);
```

- `tag_name` 必须来自 `capability_tags.name`；写入前经 `skill_mapping` / `tags_to_merge` / 别名归一。
- `source` 取 `USER_FORM` / `CHAT_CONFIRMED` / `EVIDENCE_INFERRED`，用于回答“这个能力从哪来”。

### `recommendation_feedback`（推荐反馈）

```sql
CREATE TABLE IF NOT EXISTS recommendation_feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    direction_id TEXT NOT NULL,
    feedback_type TEXT NOT NULL
        CHECK(feedback_type IN ('useful', 'irrelevant', 'too_hard', 'time_mismatch', 'goal_changed')),
    note TEXT,
    scoring_version TEXT,
    recommendation_snapshot_json TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

- v0.1 只做可观察、可审计的反馈收集，不做在线学习；`scoring_version` 与快照字段保证历史推荐可解释。

## 登录安全相关表（v0.1.5 / v0.1.9）

登录失败计数与登录审计都落在独立的小表里，**不动 `users`**：`users` 只放账号本身，
计数和审计有各自的保留期与清理任务。两张表分别由 `LoginLogRepository` / `LoginAttemptRepository`
的 `createTableIfNeeded()` 建，在 `DatabaseInitializer.init()` 里按顺序调用。

### `login_log`（登录审计）✅ v0.1.5 新增

```sql
CREATE TABLE IF NOT EXISTS login_log (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id        INTEGER,                    -- 账号不存在时为 NULL
  account        TEXT NOT NULL,              -- 客户端提交的原始账号（已截断 128）
  ip_address     TEXT,                       -- 已截断 64
  user_agent     TEXT,                       -- 已截断 256
  status         INTEGER NOT NULL,           -- 0=失败 1=成功
  failure_reason TEXT,                       -- 仅失败行，已截断 200
  created_at     TEXT NOT NULL DEFAULT (datetime('now'))   -- UTC 文本
);

CREATE INDEX IF NOT EXISTS idx_login_log_account_time ON login_log(account, created_at);
CREATE INDEX IF NOT EXISTS idx_login_log_created      ON login_log(created_at);
```

| 字段 | 说明 | 示例 |
|------|------|------|
| `user_id` | 命中的账号 ID；账号不存在时为 `NULL` | 3 |
| `account` | 客户端提交的账号原文 | '20260001' |
| `ip_address` | 客户端 IP（反代下需 `forward-headers-strategy` 才准） | '10.3.2.1' |
| `user_agent` | 请求头 UA | 'Mozilla/5.0 …' |
| `status` | `0` 失败 / `1` 成功 | 0 |
| `failure_reason` | 失败原因（账号不存在 / 密码错误累计 N 次 / 触发锁定 / 锁定中 / 被封禁 / 临时密码过期） | '密码错误，累计失败 3 次' |
| `created_at` | UTC 文本 `yyyy-MM-dd HH:mm:ss` | '2026-11-04 02:29:45' |

**保留期**：`app.security.login-log-retention-days`（默认 90 天），由 `LoginCleanupJob` 每日清理。

### `login_attempt`（登录失败计数）✅ v0.1.5 新增，v0.1.9 改分层

v0.1.9 起分两条计数线，用 `scope` 区分：

- `scope='pair'`：`(账号, IP)` 组合的连续失败，达到 `app.security.login-max-failures`（默认 5）锁
  `login-lock-millis`（默认 15 分钟）。攻击者只能锁住自己那台机器的组合，锁不到受害者在别处的登录。
- `scope='account'`：同一账号 1 小时窗口内的总失败，达到 `login-total-failures-per-hour`（默认 30）
  锁 `login-total-lock-millis`（默认 30 分钟）。换 IP 的分布式猜密码会撞上这一层。
- 管理员账号（`users.type = 2`）默认**只受 pair 层限制**（`login-admin-total-lock-enabled=false`），
  避免外网把管理员账号整体锁死。

```sql
CREATE TABLE IF NOT EXISTS login_attempt (
  scope        TEXT NOT NULL,                -- 'pair' | 'account'
  attempt_key  TEXT NOT NULL,                -- pair: '账号|IP'；account: '账号'
  failures     INTEGER NOT NULL DEFAULT 0,   -- 当前窗口/连续失败数
  window_start TEXT NOT NULL DEFAULT (datetime('now')),
  locked_until TEXT,                         -- NULL = 未锁定；UTC 文本
  updated_at   TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (scope, attempt_key)
);

CREATE INDEX IF NOT EXISTS idx_login_attempt_locked  ON login_attempt(locked_until);
CREATE INDEX IF NOT EXISTS idx_login_attempt_updated ON login_attempt(updated_at);
```

| 字段 | 说明 | 示例 |
|------|------|------|
| `scope` | 计数线：`pair` / `account` | 'pair' |
| `attempt_key` | 计数键（账号已 trim+lowercase） | '20260001\|10.3.2.1' |
| `failures` | 连续失败数（pair）或窗口内失败数（account） | 5 |
| `window_start` | 本轮计数窗口起点（account 层据此重置） | '2026-11-04 02:14:11' |
| `locked_until` | 锁定截止时间，`NULL` 表示未锁定 | '2026-11-04 02:29:45' |
| `updated_at` | 最近一次更新 | '2026-11-04 02:14:11' |

**并发安全**：计数用单条 upsert 完成，不做"先读后写"：
```sql
INSERT INTO login_attempt(scope, attempt_key, failures, window_start, updated_at)
VALUES (?, ?, 1, datetime('now'), datetime('now'))
ON CONFLICT(scope, attempt_key) DO UPDATE SET
  failures   = failures + 1,
  updated_at = datetime('now')
RETURNING failures;
```
account 层把 `failures = failures + 1` 换成
`failures = CASE WHEN window_start <= datetime('now', '-60 minutes') THEN 1 ELSE failures + 1 END`。

**锁定不续期**：`lock()` 的 `WHERE` 带 `(locked_until IS NULL OR locked_until <= datetime('now'))`，
已锁定时不会因为继续失败而延长，避免攻击者把受害者无限期锁在门外。

**保留期与清理**：`login-attempt-retention-days`（默认 30 天）后清掉非锁定的空闲行；
过期锁在读到时就删。清理任务 `LoginCleanupJob`（`@Scheduled`，cron 取 `app.security.login-cleanup-cron`，默认每小时）。

**迁移**：v0.1.5 的旧表（`attempt_key` 主键、`locked_until` 为 epoch 毫秒）在启动时由
`LoginAttemptRepository.migrateLegacyTableIfNeeded()` 自动搬进新结构，旧行按 `scope='account'` 解释
（旧语义是"按账号锁 5 次"）——升级后这些行仍有效，但适用的是 30 次那个更宽的阈值。

---

## 建表顺序

由于存在外键依赖，建表应遵循以下顺序：

```
1. users
2. feedback
3. college_credit_rules（无外部依赖）
4. user_profiles（依赖 users）
5. credit_activities（依赖 users + college_credit_rules（可选））
6. planning_history（依赖 users）
7. chat_messages（依赖 users）
8. resources（无外部依赖）
9. rag_chunks（依赖 resources.resource_id（可选））
10. capability_tags（无外部依赖）
11. capability_reference（无外部依赖，字典内容弱引用 capability_tags.name）
12. chat_usage_daily（无外部依赖）
13. recommendation_feedback（依赖 users）
14. login_attempt（无外部依赖）
15. login_log（无外部依赖，写入在事务外、失败不阻断登录）
```

> 上述建表逻辑在 `DatabaseInitializer.init()` 方法中通过 `createXxxTableIfNeeded()` 逐步执行。

---

## 索引补充（v0.0.6）

Milestone 7 管理控制台带来按用户/状态/类型/日期的分页筛选，因此在既有索引（`idx_ca_user`、`idx_ca_type`、`idx_rag_related`、`idx_rag_embedding_status`、`idx_chat_usage_date`）之外，`DatabaseInitializer.createAdminConsoleIndexes()` 在每次启动时用 `CREATE INDEX IF NOT EXISTS` 幂等补齐以下索引：

| 索引 | 表（列） | 服务的控制台视图 |
|------|----------|------------------|
| `idx_chat_messages_user_time` | `chat_messages(user_id, create_time)` | AI 对话记录（按用户+时间） |
| `idx_chat_messages_time` | `chat_messages(create_time)` | AI 对话记录（日期筛选） |
| `idx_chat_messages_role_id` | `chat_messages(role, id)` | AI 对话记录（按 role 筛选并倒序分页，避免临时排序） |
| `idx_planning_user_created` | `planning_history(user_id, created_at)` | AI 规划记录（按用户+时间） |
| `idx_planning_created` | `planning_history(created_at)` | AI 规划记录（日期筛选） |
| `idx_planning_status` | `planning_history(status)` | AI 规划记录（FAILED 排查） |
| `idx_feedback_time` | `feedback(create_time)` | 反馈与错误（日期筛选） |
| `idx_feedback_resolved_type` | `feedback(resolved, type)` | 反馈与错误（未解决系统错误） |
| `idx_chat_usage_user` | `chat_usage_daily(user_id)` | 聊天用量（按用户） |
| `idx_users_type` | `users(type)` | 用户与额度 / 总览（管理员计数） |
| `idx_credit_rules_college` | `college_credit_rules(college, credit_type)` | 加分规则 |
| `idx_credit_activities_verified` | `credit_activities(verified)` | 加分记录（审核状态） |
| `idx_resources_type_quality` | `resources(type, data_quality)` | 资源库 |
| `idx_capability_reference_section` | `capability_reference(section)` | 能力字典 |

> **无表结构变更**：v0.0.6（Milestone 6/7）没有新增表或列，仅补充索引与管理员初始化行为（`LIFECOMPOSER_INITIAL_ADMIN_PASSWORD`）。
>
> **EXPLAIN QUERY PLAN 复核（`scripts/explain-admin-queries.py`，18 个典型控制台查询）**：15 个走索引（含 covering index 与自动唯一索引），3 个为预期内全表扫描 —— `chat_messages` 按日期范围（时间戳归一化 CASE 表达式不可索引）、`users` 用户名 `LIKE '%…%'`（前置通配符）、`capability_tags` 按 category（10 行小表，走主键索引扫描）。
>
> **时间戳存储不一致（已知）**：`users.created_at` / `chat_messages.create_time` 由 JDBC `Timestamp` 写入，实际为 INTEGER epoch 毫秒；`planning_history.created_at` / `user_profiles.created_at` 由 `datetime('now')` 写入，为 TEXT。管理控制台的读查询用 `CASE WHEN typeof(col) IN ('integer','real') THEN strftime(...) ELSE col END` 归一化两种形式，因此按日期过滤的谓词不可用索引（每页仍会先命中 `user_id`/`status` 等索引列）。后续如需统一，应新增迁移一次性归一化历史数据。

---

### v0.0.6 审查整改：`users` 新增 4 列

| 字段 | 说明 |
|------|------|
| `password_reset_required` | 1 = 当前是管理员下发的临时密码，用户登录后必须先改密；改密成功置 0 |
| `temp_password_expires_at` | 临时密码失效时刻（JDBC `Timestamp`，实际存储为 epoch 毫秒）；过期即拒绝登录，改密成功置 NULL |
| `password_changed_at` | 最近一次设置密码的时间（注册/重置/自助修改都会更新） |
| `credential_version` | 密码代号，每次密码变更 +1。会话登录时记录该值，之后每次请求比对；不一致说明密码已在别处变更，服务端立即销毁该会话（无需 SessionRegistry） |

> 迁移：`UserRepository.migrateUserSchema()` 用 `PRAGMA table_info` 逐列检测并 `ALTER TABLE ADD COLUMN` 幂等补齐，`UserSchemaMigrationTest` 用真实旧版建表语句验证升级路径与默认值。
> `spring.datasource.url` 增加 `transaction_mode=immediate`，使 `@Transactional` 写事务（管理员保护、密码变更）在事务开始即取得 SQLite 写锁，避免“先查数量再更新”的并发窗口。

### v0.1.3：账号与时间约定

- **`users` 只存账号基础数据**（身份 + 凭据 + 状态）。学院/专业/年级只在 `user_profiles`。`user_profiles.student_id` 保留列但**废弃**：账号有 `student_id` 时服务端写入的是 `users.student_id` 的快照，客户端值被忽略。
- **时间戳统一为 UTC 文本 `yyyy-MM-dd HH:mm:ss`**（与 `datetime('now')`、`CURRENT_TIMESTAMP` 一致）：数据源 URL 增加 `date_class=TEXT&date_string_format=yyyy-MM-dd HH:mm:ss`，应用启动时把 JVM 默认时区固定为 UTC（`LifeComposerApplication.useUtcStorageZone()`，导入 CLI 同样调用；测试通过 surefire `-Duser.timezone=UTC`）。sqlite-jdbc 按 JVM 默认时区格式化/解析文本，所以不固定时区会让 `CURRENT_TIMESTAMP` 行偏移 8 小时。需要业务时区的地方（聊天额度、展示时间）显式传 `Asia/Shanghai`。
- **迁移**：`DatabaseInitializer.normalizeTimestampStorage()` 启动时扫描所有声明为 `TIMESTAMP`/`DATETIME` 的列，把旧版 epoch 毫秒（`typeof = 'integer'`）就地转为 UTC 文本，幂等；`TimestampStorageMigrationTest` 覆盖。
- **已知例外**：`profile_change_candidates.expires_at` 等由 `ProfileChangeService` 用 `app clock`（Asia/Shanghai）写入的短期文本不在此约定内（候选带 TTL，不参与增量同步）。

## 附录：SQLite 兼容性说明

- `BOOLEAN` 用 `INTEGER`（0/1）存储
- `CHECK` 约束用于枚举值校验（`credit_type`, `category`, `comp_level` 等），AI 录入时可减少非法数据
- `REFERENCES` 外键语法会被 SQLite 解析但默认不强制执行（`PRAGMA foreign_keys = OFF`）
- `TEXT` 字段可存储 JSON，使用 `json_extract()` 函数查询
- 索引仅建在查询频繁的列（user_id, credit_type），避免过度索引
- `datetime('now')` 和 `CURRENT_TIMESTAMP` 均可作为默认值，前者为 TEXT 格式，后者为 TIMESTAMP 格式，SQLite 两者兼容

---

*设计日期：2026-06-11*
*更新日期：2026-11-04（v0.1.9：新增「登录安全相关表」一节，补 `login_attempt`（pair/account 分层、并发安全 upsert、锁定不续期、保留期）与 `login_log`（审计字段与保留期）的 DDL 与迁移说明；建表顺序补 13–15。2026-09-23：v0.1：user_profiles 新增 version；新增 profile_change_candidates / user_capability_states / recommendation_feedback。2026-09-14：v0.0.6 Milestone 6/7，无表结构变更，补充管理控制台索引与时间戳归一化说明。2026-09-05 第二轮：移除 goals 表并入 user_profiles.goals；resources 改双键 AUTOINCREMENT id + 唯一业务 id resource_id；前轮：college_credit_rules 加 levels_json/notes，user_profiles 加 available_time/goals，新增 resources / rag_chunks / capability_tags / capability_reference）*
*参考文件：信通院创新实践活动加分办法_2023级起适用.pdf；样例/SAMPLES-README.md（数据交付包 2026-09-05 快照）*
*实际代码：LifeComposer/src/main/java/org/example/lifecomposer/Repository/*.java*
