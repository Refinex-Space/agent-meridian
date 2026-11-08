# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目阶段

AgentMeridian 是部署在企业内的多租户智能体平台：业务系统通过 Sessions/Events API（REST + SSE）使用带 Harness 能力的智能体，管理员在控制台治理模型、凭据、预算与审计。

当前处于**M0 内核与运行时核心阶段**：T01 建立了 Maven 多模块骨架与质量门禁；T02 实现了 `meridian-common`、`meridian-persistence`、`meridian-security` 的 `web` 包、`meridian-test-support` 与本地基础设施（ADR-0020）；T03 定义了 `meridian-engine-spi` 的全部契约，实现了 `HarnessAgent` 装配与实例缓存、`ScriptedModel` 与内存端口夹具，P1 通过（ADR-0023、0024、0025）；T04 实现了 `AgentScopeEngine`（执行一轮、中断、下线）、事件映射、`FencedAgentStateStore` 与首批平台中间件（ADR-0026）；T05 建立了 runtime 应用骨架、`rt` 全部核心表、轮次调度与租约（认领、续租、回收、置回队列）、事件写入与 `LeaseRegistry`、`AgentStateRepository` 的数据库实现（ADR-0027）；T06 实现了轮次 Worker、事件扇出到 Redis Stream、收件箱与工具账本、崩溃恢复与优雅下线、批次级 checkpoint，P2、P3、P10 通过（ADR-0028）。T07 写出公开 API 规范 `meridian-v1.yaml`（OpenAPI 3.1，生成链与写作约束见 ADR-0029）并实现会话、事件、线程、待处理动作接口与 SSE 事件流，开发期 API Key 认证与契约测试（ADR-0030）。T08 实现消息投递（queue、steer、中断）、四种待处理动作与恢复轮、五层权限合成与两种改写、"记住此决定"、自定义工具校验与审计，P15 通过（ADR-0031）。T09a 实现 `RoutedModel` 与生产模型工厂（OpenAI 兼容，DashScope 走 `compatible-mode/v1`）、`ModelGateway`（Redis 令牌桶限流、四级预算预扣与结算、outbox `usage` 用量）、压缩守护与上下文构成，P13 通过（ADR-0032）。T09b 实现 OpenAI 兼容的模型直通接口（`/v1/chat/completions` 流式与非流式、`/v1/embeddings`）、知识库用的内部接口（`/internal/v1/embeddings`、`/internal/v1/rerank`，规范 `meridian-internal-v1.yaml`，开发期服务凭据）与脚本化模型服务 `ScriptedOpenAiServer`（ADR-0033）。T10a 实现平台工具基类 `PlatformTool` 与六个文件工具、`none` 档工作区（`rt.workspace_entries` 与对象存储）、受保护路径、平台技能仓库、工具策略与启动自检；内核的四个提供者都有了生产实现，生产环境装配内核并认领轮次（`meridian.runtime.worker.enabled=false` 时只提供 API）（ADR-0034）。

## 交付流程

- 项目按 `docs/plan/PLAN.md` 推进：58 个任务（T01–T42 及其拆分子任务如 T09a、新增的 T19b，评审 R1–R6），每个任务一个会话，用 `/task <编号>` 启动，任务提示词在 `docs/plan/tasks/`。
- 每个任务都遵守 `docs/plan/protocol.md`：开工检查与续做、范围与决策分级（补充型写 ADR 后继续，变更型写 ADR 后停止受影响部分）、质量门禁、验收记录、收尾与报告格式。
- 验收项的唯一记录是 `docs/plan/acceptance.json`：只改本任务验收项的 `status` 与 `evidence`，只有亲自验证过才能标 `passing`。
- 工程规范：`docs/engineering/` 下的 `coding-standards.md`、`testing.md`、`frontend-design.md`、`claude-md.md`；`.claude/rules/` 中按路径生效的精要版。
- 参考源码在 `/Users/refinex/develop/project/reference/`，用法与注意事项见 `docs/plan/references.md`：带着问题查、分析评估后再采纳，不照搬；claude-code-analysis 只学机制、不复制。

## 唯一事实来源

- `docs/architecture/architecture.md` 是架构基线。写任何代码前先读对应章节；其中写"必须""禁止"的条目是硬约束。
- 与基线冲突、或要改动基线（组件选型、模块依赖、端口、API 破坏性变更、任何"必须""禁止"条目）时，先按 `docs/adr/README.md` 提 ADR，评审通过后改基线，再改代码。编号跳过 README 中预留的号段。
- `docs/research/tech-selection.md` 解释选型理由和被否决的方案；`docs/poc/README.md` 跟踪 M0 的 15 个验证项（P1–P15）。验证不通过的项必须走 ADR。
- 文档用中文；代码、命令、类名保持原文。

## 命令

Maven 命令一律在仓库根目录用 `./mvnw` 执行，单模块用 `-pl <模块> -am`。根 POM 从同一 reactor 导入 `meridian-bom`，所以 `-N`、进入子模块目录运行 Maven、或省略 `-am` 都会解析失败。

- 全量检查（格式、编译、单元与集成测试、架构测试、enforcer、覆盖率、POM 版本）：`scripts/dev/verify.sh`
- 单模块编译与单元测试：`./mvnw -pl <模块> -am test`
- 单个测试类：`./mvnw -pl <模块> -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`
- 只跑集成测试（`*IT`）：`./mvnw -pl <模块> -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`；单个再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`
- 架构测试：`./mvnw -pl meridian-arch-tests -am verify`
- 真实模型测试（只跑带 `live` 标签的 `*LiveIT`）：`./mvnw -Plive -pl <模块> -am verify`。前提：Docker 在运行、设置了 `DASHSCOPE_API_KEY`（没有时测试跳过）；模型取 `MERIDIAN_LIVE_CHAT_MODEL`（默认 `qwen-plus`）。现有用例：`./mvnw -Plive -pl meridian-runtime -am verify`（`ModelLiveIT`，约 8 次调用）
- 格式化修复（Java 与 POM）：`./mvnw spotless:apply`
- 依赖树：`./mvnw -pl meridian-engine-agentscope -am dependency:tree`
- 公开 API：改规范 `meridian-api/src/main/resources/openapi/meridian-v1.yaml` 后跑 `./mvnw -pl meridian-api -am test`（校验、兼容性检查），再跑 runtime 的契约测试；流程与写作约束见 `meridian-api/CLAUDE.md`
- 脚本化模型服务（OpenAI 兼容的本地假供应商，端到端测试与本地调试不接真实模型）：`scripts/dev/scripted-model.sh [--port=18090]`，默认端口 18090；`dev` profile 的 runtime 用模型 `mdl_dev_scripted`，用户消息以 `#script:<名称>` 选脚本，脚本清单与写法见 `meridian-test-support/CLAUDE.md`
- 下载 AgentScope 2.0.4 源码包：`./mvnw -pl meridian-engine-agentscope -am compile dependency:sources -DincludeGroupIds=io.agentscope`

`test`、`verify` 不清理 `target/`：删除或改名测试类后，旧的 class 仍会被执行，先跑 `./mvnw clean`。IDE 的后台编译器也会把带编译错误的 class 写进 `target/`，Maven 的增量编译会沿用它们；测试报 `Unresolved compilation problems` 时同样先 `clean`。

环境脚本：

- `scripts/dev/up.sh`：启动本地 PostgreSQL（pgvector + zhparser）、Redis、MinIO 并等待健康、创建桶；首次启动构建 PostgreSQL 镜像。端口 15432、16379、19000/19001，连接信息与口令见 `deploy/compose/.env.example`（本地覆盖写 `.env`，不提交）。
- `scripts/dev/down.sh [--volumes]`：停止；加 `--volumes` 才删除数据。
- `scripts/dev/run-runtime.sh [Spring 参数]`：以 `dev` profile 在本机启动 runtime（先 `up.sh`），健康检查 `/actuator/health`。
- `scripts/dev/kind-gvisor.sh [集群名]`：创建带 Calico（执行 NetworkPolicy）与 gVisor 运行时的本地 kind 集群。
- `scripts/dev/kind-verify.sh [集群名]`：验证 gVisor 生效、NetworkPolicy 被执行，输出 PASS。
- `kind delete cluster --name <集群名>`：删除集群，释放内存。

## 本机环境

- JDK 25 在 `~/develop/jdk/temurin25/Contents/Home`，已通过 `.claude/settings.local.json` 设为会话的 `JAVA_HOME`。`PATH` 上的 `java` 可能是别的版本，`./mvnw` 只认 `JAVA_HOME`，以 `./mvnw -v` 为准。
- 真实模型测试用环境变量 `DASHSCOPE_API_KEY`；已实测的端点与模型见 `docs/engineering/testing.md`。
- 慢速下载（GitHub 发布物等）用 `MERIDIAN_DOWNLOAD_PROXY`；Maven、npm、PyPI 可直连。
- 工具沙箱注入的代理变量只在宿主回环地址可用：传进容器或 kind 节点的命令必须显式设置代理。**禁止**打印代理变量、`~/.npmrc`、`~/.m2/settings*.xml` 等可能含凭据的内容。
- Docker Desktop 内存 8 GB；需要多个容器或 kind 集群时，先检查可用内存，不足时提醒用户关闭应用或调大内存。

## 整体架构

三个 Java 服务、一个 Python 服务、一个 React 控制台，加一个沙箱集群：

- `meridian-control-plane`（Spring MVC，schema `cp`）：租户、RBAC、API Key、模型目录、凭据、智能体版本、环境、预算、用量账本、审计。
- `meridian-runtime`（Spring WebFlux，schema `rt`）：会话、事件、SSE、轮次调度与租约、执行智能体、平台工具、沙箱生命周期、模型调用与计量。
- `meridian-knowledge`（Spring MVC，schema `kb`）：知识库写入与混合检索，完全自研，不依赖任何智能体框架。
- 服务只读写自己的 schema，彼此只走 HTTP；用量与审计走 outbox → Redis Stream → control-plane 消费者。

核心分工：**平台掌控会话语义，AgentScope 只执行一轮。** 会话、事件（`rt.session_events`，对外唯一事实来源）、收件箱（queue / steer）、待处理动作（审批、自定义工具、恢复确认）、租约与崩溃恢复，都是平台的数据和代码。AgentScope Java 2.0.4 的 `HarnessAgent` 只被 `AgentEngine.runTurn(ctx, TurnInput)` 调用来执行一轮。

关键数据流：API 写事件和收件箱，插入 `rt.turns(queued)` → Worker 用 `FOR UPDATE SKIP LOCKED` 认领（同事务占用租户并发名额）→ `runTurn` 产出的事件先写 PostgreSQL，再 XADD 到 Redis Stream → SSE 订阅 Redis，并按 `seq` 从 PostgreSQL 补发、去重、跳号回补。

## 模块边界

- **只有** `meridian-engine-agentscope`（及其测试模块）可以引用 `io.agentscope..`。runtime 只能在 `config` 包里引用它的装配入口。
- 引擎需要的 runtime 能力通过 `meridian-engine-spi` 的端口接口获取（`LeaseRegistry`、`InboxPort`、`ModelGateway`、`EventSinkPort` 等），由 runtime 实现。runtime 需要内核做的事，通过 `AgentEngine`、`SandboxDriver`、`ModelInvoker` 获取。端口签名里不能出现 AgentScope 类型。
- `PlatformContext` 在 `meridian-common`。
- 每个业务上下文内部分 `api` / `app` / `domain` / `infra` 四层，`domain` 不依赖 Spring 和 `infra`。
- 仓储继承 `TenantScopedRepository`；豁免只限架构基线"租户隔离"一节列出的端口实现类。子类只能经 `sql(...)` 写 SQL，语句必须引用 `:tenant_id`（项目级还要 `:project_id`），值由基类从 `PlatformContext` 绑定；跨租户的继承 `SystemRepository`，放在 `infra.system` 并标注 `@CrossTenant`。runtime 中所有 JDBC 经 `common.concurrent.Blocking`。
- 禁止依赖：Spring AI、LangChain4j、AgentScope 的 Spring Boot starter、RAG 扩展、Service 模块。
- Java 包根与 Maven groupId：`cn.refinex.meridian`。依赖版本只在 `meridian-bom` 声明。不直观的模块包根：`engine-spi` → `engine.spi`，`engine-agentscope-test` → `engine.agentscope.testkit`，`model-client` → `modelclient`，`control-plane` → `controlplane`，`test-support` → `testsupport`；不在已登记包根下的类会被 ArchUnit 拒绝。
- 依赖规则由构建强制：maven-enforcer 默认拒绝平台模块与 AgentScope 构件，每个模块在自己 POM 的 `enforce-rules` 中按「模块依赖规则」放行（ADR-0022）；包层面由 `meridian-arch-tests` 的 ArchUnit 规则检查，模块表在 `MeridianModule`。

## 集成 AgentScope 时最容易写错的地方

这些都已在 AgentScope `v2.0.4` 源码核实，细节见架构基线"运行时架构"一节：

- 状态存储用平台自研的 `FencedAgentStateStore`，每次写入在同一事务里校验租约。失去租约时只能取消订阅，禁止调用 `interrupt`，因为中断会触发 AgentScope 写状态。
- 崩溃恢复按"本轮是否写过状态内容"判定（`agent_states.content_turn_id`），不能只比较 `content_epoch`：`lease_epoch` 每轮从 1 开始（ADR-0028）。
- 每轮开始前、结束后（在 `finally` 中）都要 `clearStateCache(userId, threadId)`，再用 `getDelegate().replacePermissionContext(...)` 安装合成后的权限。
- `RuntimeContext.sessionId` 是线程 ID（`thr_…`），不是会话 ID。命名空间等会话级的值从 `PlatformContext` 取。
- 子线程上下文必须用 `RuntimeContext.builder()` 从空白构建，禁止 `builder(parent)`。
- 一轮的输入是四类 sealed `TurnInput` 之一，审批结果和自定义工具结果不能与普通消息混在同一轮。
- 显式 ask 规则在 `DONT_ASK` 下仍返回 ask，无人审批的执行（`unattended`、子线程）要在合成时把 ask 改写为 deny；平台安全规则让权限上下文永远不"平凡"，`DEFAULT` 模式下没有命中 allow 的工具都要确认，智能体必须显式放行工具（ADR-0031）。
- `HarnessAgent` 的装配清单（`disableWebTools`、`disableToolsConfig`、`disableSubagents`、`disableTranscript`、`maxRetries(1)`、不设 `fallbackModel`、`NoopAsyncToolRegistry` 等）以"引擎装配"表为准，由 `AgentAssemblerTest` 逐项断言。`maxRetries` 是含首次的总尝试次数，0 会抛异常（ADR-0023）。
- 平台工具继承 `PlatformTool`（它继承 `ToolBase`、实现 `matchRule`、记账与幂等键），新增工具的步骤见 `meridian-engine-agentscope/CLAUDE.md`；文件和命令操作经 `call.filesystem()` 并传入 `call.runtimeContext()`。
- 工作区命名空间 `[tenant, project, end_user, session]` 取自 `PlatformContext`（含 `endUserId`），不取 `RuntimeContext.getSessionId()`（线程 ID）。`wait_async_results` 在 2.0.4 中是 `ToolBase`；拒绝工具用权限规则，`onActing` 中间件合成的事件不会被发布（ADR-0034）。
- 限流、预算、计量、重试与降级在 `RoutedModel` 内完成；压缩器会吞掉模型异常，由 `CompactionGuardMiddleware` 兜住：`CompactionMiddleware` 在调用下游之前就把摘要写进状态，失败时内层先恢复上下文再抛异常（ADR-0032）。
- `AgentEventType`（31 个）和 `GenerateReason` 的映射必须穷举，由单元测试保证。

## 测试

测试类名决定由谁运行：`*Test` 由 Surefire 跑，`*IT` 由 Failsafe 在 `verify` 阶段跑，`*LiveIT` 必须带 JUnit 标签 `live`，只在 `-Plive` 时运行；不符合命名的测试不会被执行。

所有智能体行为必须能用 `ScriptedModel`（在 `meridian-engine-agentscope-test`）这个脚本化假模型跑通，测试不依赖真实模型；`meridian-engine-agentscope` 的装配与场景测试也放在该模块（反向依赖会成环），它可以依赖 common 与 engine-spi（ADR-0025）。集成测试使用 Testcontainers（PostgreSQL、Redis、MinIO）；runtime 测试中开启 BlockHound。
