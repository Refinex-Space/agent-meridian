# T20c 控制台原型与设计评审

| 项 | 内容 |
|---|---|
| 阶段 | 4 M1 控制台（并行轨道） |
| 依赖 | T20b |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 中 |
| 关联验证项 | 无 |

启动方式：在 `../agent-meridian-console` worktree 中开新会话，输入 `/task T20c`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 工作区

T20a 至 T20c 是与后端任务并行的控制台设计轨道，必须在独立的 worktree 与分支中执行，不在主工作区执行：

```bash
# 第一次（T20a 开工前，T07 已在 main 上完成）由用户在主工作区执行：
git worktree add ../agent-meridian-console -b console/T20 main
# worktree 不包含被 Git 忽略的本机配置（JAVA_HOME、参考源码目录），复制一份：
cp .claude/settings.local.json ../agent-meridian-console/.claude/
# 之后 T20a、T20b、T20c 的会话都在 ../agent-meridian-console 中启动
```

进度只写在 `PLAN.md` 的「控制台设计轨道」一节（轨道看板、轨道检查点、轨道会话日志）和 `acceptance.json` 中本任务的验收项，不改主看板与主检查点，避免合并冲突。T20c 完成后，由用户把 `console/T20` 合并回 `main` 并移除 worktree。

## 背景与目标

组件齐了，本任务用它们拼出控制台的页面原型，并做完整的截图评审。这些原型是 T21 至 T25b 的验收基准：页面任务的成品要与原型一致。

## 必读

1. `docs/engineering/frontend-design.md` 全文，尤其"布局""状态与反馈""设计流程与评审"。
2. 架构设计「控制台」一节（信息架构）。
3. `console/CLAUDE.md`。

## 范围

### 要做

- 在 Storybook 中拼出：应用外壳、四种页面模板、会话列表、智能体编辑、待处理动作、概览（会话详情已在 T20a 完成）。
- MSW 模拟数据：中文的智能体名称、会话内容、工具调用、待处理动作、用量数字，贴近真实，不用占位文字。
- 截图评审至少两轮，最后一轮做减法。

### 不做

- 路由、认证、真实 API 调用（T21）。

## 交付物

### 1. 原型

- 事件、会话等运行时数据使用 T20a 从 `meridian-v1.yaml` 生成的类型。管理类数据（智能体、环境、用量等）此时还没有正式的接口定义，原型使用 `src/prototypes/fixtures/` 中的视图模型类型，不假装是 API 类型；页面任务接入真实接口时编写映射。
- 每个原型覆盖加载、空、错误、无权限、长内容、实时更新这些状态中适用的部分。

### 2. 截图评审

- 用 `ego-browser` 技能（或 Playwright）打开 Storybook，对每个原型页面在亮暗两种主题、1280 与 1920 两种宽度下截图，保存到 `tmp/evidence/T20c/`。
- 对照 `frontend-design.md` 的评审表逐项检查，写出问题清单，修正后再截一轮。至少两轮。
- 最后一轮做减法：每个页面去掉一样不必要的东西，记录去掉了什么。
- 评审记录（问题、修正、结论）写进报告。

## 验收标准

- [T20c-A01] 原型页面齐全（外壳、四种模板、会话列表、智能体编辑、待处理动作、概览），数据真实感强，没有占位文字。验证：截图。
- [T20c-A02] 至少两轮截图评审，问题清单与修正记录完整；禁用清单中需要人工检查的条目逐条无违反。验证：报告与 `tmp/evidence/T20c/` 中的截图。
- [T20c-A03] 最后一轮的减法已完成，每个页面记录了去掉的内容。验证：报告。
- [T20c-A04] `pnpm -C console lint`、`typecheck`、`test`、`build-storybook` 全部通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行，进度写在「控制台设计轨道」一节。另外：

- 报告开头提醒用户：把 `console/T20` 合并回 `main`（`git merge console/T20`），再移除 worktree（`git worktree remove ../agent-meridian-console`）；合并后才能开始 T21。
- 更新 `console/CLAUDE.md`：原型的位置与用法。
