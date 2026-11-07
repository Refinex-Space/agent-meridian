# T20b 控制台基元与领域组件

| 项 | 内容 |
|---|---|
| 阶段 | 4 M1 控制台（并行轨道） |
| 依赖 | T20a（视觉方向已确认） |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在 `../agent-meridian-console` worktree 中开新会话，输入 `/task T20b`，或粘贴本文件全部内容。

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

T20a 定下了视觉方向，并经用户确认。本任务按这个方向做出其余全部基元与领域组件。后续的页面任务只组合这些组件，不各自发明；所以每个组件都要覆盖全部状态，并有测试。

## 必读

1. `docs/engineering/frontend-design.md` 全文，尤其"设计定稿""自研组件""状态与反馈""交互规范"。
2. `console/CLAUDE.md`（T20a 产出）。
3. T20a 的截图与用户的确认意见（`acceptance.json` 中 [T20a-A07] 的证据）。

## 范围

### 要做

- 其余基元：用 shadcn/ui CLI 生成源码到 `src/components/ui/`，按令牌重塑（不保留默认的 zinc 主题、圆角、阴影），图标全部替换为 Phosphor 的封装。至少包括：输入框、文本域、选择器、复选框、单选、开关、对话框、抽屉、下拉菜单、弹出层、表格、分隔线、命令面板（cmdk）、通知（Sonner）。
- 领域组件：`frontend-design.md`「自研组件」表中除 `SessionTimeline` 以外的全部组件，放在 `src/components/domain/`。
- 三张空态插画（自研 SVG，与 T20a 的图标同一套网格与线宽）。

### 不做

- 页面原型（T20c）。
- 路由、认证、真实 API 调用（T21）。

## 交付物

- 每个组件的 story 覆盖全部状态：默认、悬停、聚焦、禁用、加载、空、错误、长内容；亮暗两套主题。
- 每个领域组件有 Vitest 测试，覆盖其行为（复制反馈、URL 同步、权限隐藏与禁用、删除确认的名称输入等）。
- `DataTable` 的虚拟滚动与 `FilterBar` 的 URL 同步各有交互测试。

## 实现要点

- 组件只用令牌，不出现任何硬编码颜色与尺寸；新组件先查"自研组件"表，表里没有的先在表中加一行。
- 生成的 shadcn 源码改动后，在文件头注释中说明与上游的差异，便于以后升级时比对。

## 验收标准

- [T20b-A01] 基元与领域组件齐全，每个组件的 story 覆盖规定的状态；Storybook a11y 检查零违规。验证：Storybook 构建输出与 a11y 报告。
- [T20b-A02] 代码中没有 `lucide-react` 与 `@radix-ui/react-icons` 的导入，lint 通过。验证：lint 输出与代码搜索。
- [T20b-A03] 领域组件的 Vitest 测试全部通过。验证：测试报告。
- [T20b-A04] 组件在亮暗两种主题下的截图与 T20a 确认的方向一致（同一套颜色、字体、间距、圆角、阴影）。验证：截图（`tmp/evidence/T20b/`）。
- [T20b-A05] `pnpm -C console lint`、`typecheck`、`test`、`build-storybook` 全部通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行，进度写在「控制台设计轨道」一节。另外：

- 更新 `console/CLAUDE.md`：组件清单的位置、新增组件的流程。
