# T21 控制台工程基座

| 项 | 内容 |
|---|---|
| 阶段 | 4 M1 控制台 |
| 依赖 | T20c（`console/T20` 已合并回 `main`）、R2 |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T21`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

T20a 至 T20c 完成了设计系统与原型。本任务搭建控制台的工程基座，让后续页面任务只需关注业务：应用外壳与路由、登录、API 客户端、错误处理、加载与空态的统一模式、SSE 客户端、权限控制、主题切换、端到端测试框架。

## 必读

1. `docs/engineering/frontend-design.md`（尤其「布局」「状态与反馈」「交互规范」「前端工程」）。
2. 架构设计「控制台」「安全架构」中控制台用户的认证方式、「API 设计」中的「SSE 协议」。
3. `console/CLAUDE.md` 与 T20a、T20c 的原型。
4. T19b 产出的"页面到接口"对照表（`docs/engineering/console-api-map.md`）。

## 范围

### 要做

- 应用外壳：左侧导航（可折叠）、项目切换、面包屑、⌘K 命令面板、用户菜单；导航结构按架构设计「信息架构」。
- 路由：TanStack Router，每个一级导航一个路由模块（页面内容留给后续任务，先放空态）；筛选条件放 URL 搜索参数。
- 登录：OIDC 授权码加 PKCE（本地 Keycloak）；access token 只放内存，静默续期；未登录跳转登录；退出。
- API 客户端：用 openapi-typescript 从 `meridian-v1.yaml` 与 `meridian-admin-v1.yaml` 生成类型，openapi-fetch 调用；统一注入 `Authorization` 与 `Meridian-Project` 头；类型生成接入构建，规范变化时类型自动更新。
- 错误处理：problem+json 映射为 `ErrorState` 或通知，按 `frontend-design.md` 的通知策略执行；带可复制的 `request_id`。
- 数据加载模式：TanStack Query 的统一配置（重试、失效、错误）；区域级 Suspense 与骨架（150ms 延迟、300ms 最短显示）。
- SSE 客户端：基于 `fetch` 流式读取（`EventSource` 不能携带 `Authorization` 头），实现架构设计「SSE 协议」中客户端侧的去重、`Last-Event-ID` 续传、断线重连；封装为 Hook，配合 `StreamIndicator`。
- 权限：按用户在项目中的角色，用 `PermissionGate` 控制操作的显示与可用。
- 主题：亮色、暗色、跟随系统，记在本地。
- 全局横幅：与服务端断开、预算告警。
- Playwright：登录、导航、主题切换的端到端用例；亮暗主题截图。
- CI：控制台的 lint、typecheck、test、build、Storybook 构建、Playwright（在 CI 中用 compose 的 `app` 与 `auth` profile 起后端、Keycloak 与脚本化模型服务）。

### 不做

- 各页面的业务功能（T22 至 T25b）。

## 实现要点

- 生成的 API 类型禁止手改，也禁止在业务代码中手写接口类型。
- SSE 客户端的单元测试模拟断线、乱序、重复事件、跳号，验证最终事件序列连续且不重复。
- 外壳与路由的视觉必须与 T20c 的原型一致；用 `toHaveScreenshot` 建立截图基线，之后的页面任务只允许内容区与基线不同。
- Playwright 默认连接脚本化模型服务（`ScriptedOpenAiServer`，T09b），结果确定；只有标注 `live` 的用例才调用真实模型。

## 验收标准

- [T21-A01] 用 Keycloak 测试用户登录后进入控制台，令牌只在内存中（刷新页面后静默续期成功）；退出后受保护路由跳转登录。验证：Playwright 用例。
- [T21-A02] 导航结构与架构设计一致，折叠状态被记住；⌘K 能跳转到任一页面。验证：Playwright 用例与截图。
- [T21-A03] API 类型由规范生成，构建时自动更新；代码中没有手写的接口类型。验证：构建配置与代码搜索。
- [T21-A04] problem+json 错误按通知策略呈现，`request_id` 可复制。验证：组件测试与截图。
- [T21-A05] SSE 客户端在断线、乱序、重复、跳号四种情况下得到连续不重复的事件序列。验证：单元测试。
- [T21-A06] 权限：观察者角色看不到写操作，或操作被禁用并说明原因。验证：Playwright 用例。
- [T21-A07] 用 `toHaveScreenshot` 为应用外壳与四种页面模板建立亮暗主题、1280 与 1920 宽度的截图基线，并与 T20c 原型逐一对照一致。验证：基线文件与对照截图（`tmp/evidence/T21/`）。
- [T21-A08] CI 中控制台的全部检查通过。验证：CI 配置与本地等效命令的输出。
- [T21-A09] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `console/CLAUDE.md`：新增页面的步骤（路由、API Hook、加载与错误模式、权限、测试、截图）。
