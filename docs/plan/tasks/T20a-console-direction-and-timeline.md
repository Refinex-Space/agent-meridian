# T20a 控制台设计方向：工程、令牌、图标与时间轴原型

| 项 | 内容 |
|---|---|
| 阶段 | 4 M1 控制台（并行轨道） |
| 依赖 | T07（在独立 worktree 中与后端任务并行） |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在 `../agent-meridian-console` worktree 中开新会话，输入 `/task T20a`，或粘贴本文件全部内容。

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

控制台的品质标准是"专业、克制、有辨识度"，交付的界面不能有 AI 模板的痕迹。做到这一点的前提，是先定下视觉方向，再大量做组件与页面。

本任务定方向：搭好 `console/` 工程与守护规则，完成设计计划、令牌、字体、图标，以及最能体现方向的一处，即 `SessionTimeline` 与会话详情原型。完成后提交截图，**停下来等用户确认视觉方向**。方向确认后，T20b 做其余组件，T20c 做其余原型与评审。方向一旦被后面五个页面任务使用就很难再改，所以这道关口不能跳过。

## 必读

1. `docs/engineering/frontend-design.md` 全文（本任务的主要依据）。
2. 架构设计「控制台」一节、「API 设计」中的事件类型与 SSE 协议。
3. `meridian-api` 中的 `meridian-v1.yaml`（T07 产出；原型中的事件数据按它的 schema 编写）。
4. `docs/engineering/testing.md`「前端测试」。
5. 调用 `frontend-design` 技能，按它的方法做设计计划与自查。
6. 参考：`docs/plan/references.md` 中控制台相关的仓库（只参考信息组织与交互，不照搬视觉）。

## 范围

### 要做

- `console/` 工程初始化：Vite、React 19、TypeScript 严格模式、pnpm、ESLint（typescript-eslint、react-hooks、jsx-a11y、导入边界规则）、Prettier、Vitest、Testing Library、Storybook（含 a11y 插件）、MSW、Playwright、openapi-typescript。
- 守护规则：颜色 lint、图标库 lint、禁用清单中能机检的条目。
- 设计计划、令牌、字体、图标。
- 会话详情原型用到的基元：按钮、徽标、标签页、提示、滚动区域、骨架。
- `SessionTimeline` 与会话详情原型。
- ADR-0021（编号已预留）：前端依赖选型与版本（补充型），依据 `frontend-design.md`「组件与依赖选型」表。
- 视觉方向确认关口。

### 不做

- 其余基元与领域组件（T20b）、其余原型与完整评审（T20c）。
- 路由、认证、真实 API 调用（T21）。

## 交付物

### 1. 设计计划（先做，再写代码）

在 `frontend-design.md` 末尾新增"设计定稿"一节，写入：

- 令牌草案：色板（亮暗两套，OKLCH 值与对应的十六进制值）、字号阶梯、间距、圆角、阴影、动效。
- 布局概念：应用外壳与四种页面模板的 ASCII 线框。
- 自查：逐条对照"禁用清单"与 `frontend-design` 技能列出的五类常见模板化特征，写明本设计在每一条上是怎么避开的；任何一处读起来像"对任何同类产品都会这样做"的默认值，改掉并写明改了什么、为什么。

### 2. 工程与守护规则

- lint：组件中禁止十六进制颜色、`rgb()` 与 Tailwind 任意色值；`no-restricted-imports` 禁止 `lucide-react` 与 `@radix-ui/react-icons`；渐变（`bg-gradient-*`、`linear-gradient`）、`backdrop-blur`、`uppercase` 与 `tracking-*` 同时使用、按钮上的 `rounded-full`、JSX 文本中的 emoji 与"→"，写成 lint 规则或检查脚本。
- 从 `meridian-v1.yaml` 生成 TypeScript 类型（openapi-typescript），MSW 处理器与原型数据使用这些类型。
- `pnpm -C console lint`、`typecheck`、`test`、`build-storybook` 接入 `scripts/dev/verify.sh`。

### 3. 令牌、字体与图标

- `src/styles/tokens.css`：OKLCH 定义的 CSS 变量，亮暗两套；Tailwind 4 的 `@theme` 映射。
- 字体：IBM Plex Sans 与 IBM Plex Mono 用 `@fontsource`；IBM Plex Sans SC 用 `@ibm/plex-sans-sc`，构建期按 `unicode-range` 切片（例如 cn-font-split），只打包 400、500、600 三个字重。
- Storybook 令牌页：色板（含对比度数值）、字号、间距、圆角、阴影、动效演示。
- Phosphor Icons 的封装：统一尺寸（16、20、24）与 `currentColor`。
- 自研 SVG：品牌标识（子午线弧加刻度）与七个领域图标（智能体、线程、轮次、沙箱、待处理动作、技能、知识库）；按 Phosphor 的网格与线宽绘制，SVGO 优化，SVGR 生成组件；Storybook 中与 Phosphor 图标并排展示。

### 4. `SessionTimeline` 与会话详情原型

- `SessionTimeline` 是全控制台唯一的编排动效，按规范实现"子午线"、子线程分叉、"此刻"标记与新事件落位动画；`prefers-reduced-motion` 下退化为不透明度变化；虚拟滚动。
- 会话详情原型：时间轴工作台，含运行中的实时追加（MSW 模拟 SSE）、待处理动作、子线程。事件数据用中文、贴近真实（自己编写，不要用"示例""测试"这类占位文字）。

### 5. 视觉方向确认关口

- 用 `ego-browser` 技能（或 Playwright）对令牌页、图标页、会话详情原型，在亮暗两种主题、1280 与 1920 两种宽度下截图，保存到 `tmp/evidence/T20a/`。
- 对照 `frontend-design.md` 的评审表自查一轮并修正。
- 报告中给出截图路径、设计计划要点、自查中改掉的默认值。然后把轨道看板中 T20a 的状态改为"阻塞（等待用户确认视觉方向）"并停止。
- 用户确认后（同一编号新开会话续做），按用户意见修改，把 [T20a-A07] 标为 `passing`（证据写明确认日期与意见），完成本任务；用户要求调整方向时，修改后重新截图，再次停在关口。

## 验收标准

- [T20a-A01] "设计定稿"一节已写入，自查逐条完成，并写明修改过的默认值。验证：文档 diff。
- [T20a-A02] 令牌亮暗两套齐全；正文对比度不低于 4.5:1，大字与图标不低于 3:1（令牌页显示数值）。验证：Storybook 令牌页截图。
- [T20a-A03] lint 能拦截硬编码颜色、`lucide-react` 导入与能机检的禁用条目。验证：逐条临时写入违规代码时 lint 失败，撤销后通过。
- [T20a-A04] 自研图标与 Phosphor 图标并排时线宽与视觉重量一致。验证：截图。
- [T20a-A05] `SessionTimeline` 实现子午线、子线程分叉、"此刻"标记、新事件落位动画；减少动效模式下只有不透明度变化。验证：截图或录屏。
- [T20a-A06] 时间轴性能：Playwright 在固定的 1 万条事件数据上连续滚动 5 秒，帧时间 p95 不超过 16.7 ms，没有超过 50 ms 的长任务。验证：Playwright 输出的帧时间统计。
- [T20a-A07] 用户已确认视觉方向。验证：确认日期与意见（只有用户在对话中明确确认后才能标 `passing`）。
- [T20a-A08] ADR-0021 已写，依赖版本均为最新稳定版且发布至少两周。验证：ADR 与 `package.json`。
- [T20a-A09] `pnpm -C console lint`、`typecheck`、`test`、`build-storybook` 全部通过，并已接入 `scripts/dev/verify.sh`。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行，进度写在「控制台设计轨道」一节。另外：

- 创建 `console/CLAUDE.md`：命令、目录结构、令牌与组件的使用规则、禁用清单的链接、新增组件的流程。
- 在根 `CLAUDE.md` 的命令一节加入控制台的命令。
