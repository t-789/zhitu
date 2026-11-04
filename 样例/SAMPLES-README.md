# 样例数据包说明

大创项目「基于能力画像的大学生跨学科成长路径规划智能体」的数据交付包（2026-09-05 快照）。
数据负责人：陆子粤。数据问题找陆子粤，字段规范全文见仓库 `docs/data-schema.md`。

## 目录结构

| 文件 | 内容 | 数量 |
| --- | --- | --- |
| `data/resources.json` | 成长资源库（竞赛 + 课程同文件，用 `type` 区分） | 18 条（竞赛 12 + 课程 6） |
| `data/student_profiles.json` | 虚构学生画像（已脱敏，非真人数据） | 3 条 |
| `rag/chunks.json` | RAG 切片（程序化生成，禁止手写编造） | 28 条 |
| `rag/test_questions.json` | RAG 检索测试问题（含期望命中切片） | 10 个 |
| `draft/capability-tags.json` | 能力标签权威定义（唯一来源，勿在别处复制） | 10 标签 |
| `extracted/*.json` | 北邮各学院加分规则（从 PDF 提取） | 346 条 / 6 学院 |

## data/resources.json 怎么读

竞赛与课程在同一个文件里，靠 `type` 区分（`competition` / `course`）。

**竞赛核心字段**（以 competition_002 蓝桥杯为例）：

```json
{
  "id": "competition_002",
  "type": "competition",
  "levels": ["provincial", "national"],
  "stages": [{"stage": "provincial", "month": "4月", "format": "省赛（个人赛）"}, ...],
  "target_majors": {"categories": ["工科"], "majors": ["计算机科学与技术", ...]},
  "required_skills": {"profile": "algorithm_contest"},
  "difficulty": "easy",
  "bonus_point": {"_note": "...", "北邮计算机学院参考": "A 级竞赛"}
}
```

- `required_skills` 是**引用**不是列表：`profile` 指向 `capability-tags.json` 的 `skill_profiles` 模板，可带 `extra` / `exclude` 增删。使用前必须先展开（仓库 `scripts/demo_usage.py` 的 `resolve_skills()` 有现成实现）。
- `target_majors` 用学科大类（`major_categories` 定义大类→专业映射），不再枚举专业名。
- `bonus_point` 来自各学院认定细则（保研加分档位），`_note` 记录了来源文件；key 与 `levels` 对应。

**课程额外字段**（type=course）：`provider`（开课单位）、`course_link`、`description`（课程介绍）、`teaches_skills`（学完培养哪些能力标签）。

**缺失值约定**：没确认的信息一律写 `unknown` 或留空 + `_notes` 说明，**没有编造值**。报名日期每年变化，只对已确认的条目填了精确值。

## data_quality 三级

| 标记 | 含义 |
| --- | --- |
| `complete` | 关键字段有可信来源双重确认 |
| `partial` | 必填齐全，部分可选字段未确认（多为报名日期 unknown） |
| `needs_review` | 来源为二手转载或官网未核成（本包中：计设 004、高校计算机 009、集创赛 013、北邮课内 course_006） |

## rag/ 怎么用

- `chunks.json` 28 条切片来自四类结构化数据：学院加分规则概览 ×6、竞赛 ×12、课程 ×6、能力标签说明 ×2、另加 2 条早期手写切片。每条带 `source_url` / `related_resource_id` 可回查。
- `test_questions.json` 的 `expected_chunk_ids` 用于验收检索效果：仓库 demo 脚本 2-gram 关键词检索已达 10/10 命中；正式系统换 Embedding 相似度后仍可用它做回归测试。
- 双创分相关规则分散在多个学院文件，命中任一学院概览切片均属合理。

## capability-tags.json（推荐模块必读）

- 10 个标准标签：编程基础、算法与数据结构、数学建模、数据分析、AI 工具应用、文档写作、表达展示、团队协作、项目经验、英语阅读，分技术/通用两类。
- 每个标签有 L1/L2/L3 等级描述，用于解释「差在哪、怎么补」。
- `skill_mapping`：学生自填技能词 → 标准标签（如「C语言」→ 编程基础），匹配前先过这层。
- `skill_profiles` / `role_profiles`：竞赛技能模板 / 团队角色模板。
- 新增标签前先查 `tags_to_merge`，确认无法合并再新增，并补全等级与别名。

## extracted/ 加分规则字段

每条规则含：`college`（学院）、`credit_type`（`recommendation`=保研加分 / `graduation`=毕业双创分）、`comp_level`（S / A+ / A / B+ / B 认定级）、`comp_name` / `award_tier`（竞赛与奖项）、`credits`（分值）、`category_cap`（类别累计上限）、`student_cohort`（适用年级，多数为 2022 级起）、`doc_source`（来源 PDF）。

## 验收与演示

完整仓库里 `python scripts/demo_usage.py` 可跑三视角演示：后端导入（18 条资源入库模拟）、推荐匹配（3 画像 × 18 资源矩阵）、RAG 检索（10 问命中率统计）。
