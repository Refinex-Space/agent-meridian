---
paths:
  - "docs/**/*.md"
  - "**/CLAUDE.md"
  - "README*.md"
---

# 文档规则（精要）

- 架构设计是唯一基线。改动其中已定内容先写 ADR（`docs/adr/`，流程见 `docs/adr/README.md`），评审通过后再改正文并递增版本号。
- M2、M3 的设计增补写在 `docs/architecture/addenda/`，只补充不改基线；由 R5、R6 并入正文。
- M0 验证报告写在 `docs/poc/`，命名 `PNN-短标题.md`，并更新 `docs/poc/README.md` 的状态。
- `docs/plan/acceptance.json`：只能改本任务验收项的 `status` 与 `evidence`；禁止删除或改写描述。
- `docs/plan/PLAN.md`：任务开工与收尾时按 `docs/plan/protocol.md` 更新看板、检查点、会话日志。
- CLAUDE.md 分层与长度规则见 `docs/engineering/claude-md.md`：根文件 200 行以内，模块文件 120 行以内，只写读代码看不出来、每次都需要的信息。
- 写作：中文，先结论后理由，肯定句，少限定语，不写"本文将"，标题平实；中英文之间留空格；需要时使用 `doc-craft` 技能体检。
