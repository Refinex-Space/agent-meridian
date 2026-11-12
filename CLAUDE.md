# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目阶段

AgentMeridian 是部署在企业内的多租户智能体平台：业务系统通过 Sessions/Events API（REST + SSE）使用带 Harness 能力的智能体，管理员在控制台治理模型、凭据、预算与审计。

M0（内核与运行时核心，T01–T13）有条件完成，R1 的结论与进入 M1 的前提见 `docs/poc/M0-总结.md`；当前是 M1 知识库与后端收口（T18 起）。已实现的是 control-plane 的租户体系（租户、项目、成员与角色、API Key、限流、租户并发配额，T14a）、身份（OIDC 控制台用户、服务 JWT 的签发，T14b）与凭据库、供应商账号、模型目录、价格表（T15a，runtime 的模型调用经内部接口解析）、智能体与版本、环境、技能、MCP 服务、权限策略、租户设置及其分发（T15b，runtime 的智能体配置经内部接口读取，`config.invalidate` 通知失效）、用量账本与审计日志的消费与查询、四级预算（Redis 限额与每小时对账）、用量报表（T16，runtime 的预算限额读 control-plane 写进 Redis 的值，新租户默认零预算）、Webhook 的配置（T17a，投递与投递记录在 runtime）、runtime（会话、事件与 SSE、调度与租约、崩溃恢复、投递与待处理动作、权限合成、模型路由与计量、平台工具、会话沙箱与出网、子智能体、Webhook 投递、会话清理与事件归档、保留，T17a；上传文件与引用、工作区 API、`session.usage`、`/v1/responses` 子集，T17b）、knowledge 的写入（知识库与文档的公开 API、`ingestion_jobs` 队列、Tika/PDFBox/doc-parser 解析、切分、经 runtime 的 Embedding、存储计量，T18；检索在 T19）、model-client、doc-parser（Docling）、engine-agentscope、common、persistence、security、OpenAPI 规范、沙箱镜像与 chart；console 尚未建立。每个任务交付了什么、对应哪个 ADR，见 `docs/plan/PLAN.md` 的会话日志与 `docs/adr/README.md`。

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
- kind 集群测试（只跑带 `kind` 标签的 `*KindIT`）：`./mvnw -Pkind -pl meridian-engine-agentscope-test -am verify`（驱动），`./mvnw -Pkind -pl meridian-runtime -am verify`（会话沙箱端到端，另需 Docker；`kind`、`live` 两个 profile 都跳过 Surefire），前提是下面「环境脚本」中的 kind 集群与沙箱已部署
- 沙箱执行服务（Python）：`cd sandbox-runtime && uv run pytest -q`；镜像内测试 `docker build --target test sandbox-runtime`
- 文档解析服务（Python）：`cd doc-parser && uv run pytest -q`（不需要模型的用例）；镜像内测试（含 Docling 模型的 OCR 与表格用例）`docker build --target test doc-parser`；knowledge 的集成测试会自己构建并启动 doc-parser 容器
- 真实模型测试（只跑带 `live` 标签的 `*LiveIT`）：`./mvnw -Plive -pl <模块> -am verify`。前提：Docker 在运行、设置了 `DASHSCOPE_API_KEY`（没有时测试跳过）；模型取 `MERIDIAN_LIVE_CHAT_MODEL`（默认 `qwen-plus`）。现有用例：`./mvnw -Plive -pl meridian-runtime -am verify`（`ModelLiveIT`，约 8 次调用）；`./mvnw -Plive -pl meridian-knowledge -am verify`（`IngestionLiveIT`，用真实 Embedding 写入一份文档，约 2 次调用）
- 格式化修复（Java 与 POM）：`./mvnw spotless:apply`
- 依赖树：`./mvnw -pl meridian-engine-agentscope -am dependency:tree`
- 公开 API：改规范 `meridian-api/src/main/resources/openapi/meridian-v1.yaml` 后跑 `./mvnw -pl meridian-api -am test`（校验、兼容性检查），再跑 runtime 的契约测试；流程与写作约束见 `meridian-api/CLAUDE.md`
- 脚本化模型服务（OpenAI 兼容的本地假供应商，端到端测试与本地调试不接真实模型）：`scripts/dev/scripted-model.sh [--port=18090]`，默认端口 18090；`dev` profile 的 runtime 用模型 `mdl_dev_scripted`，用户消息以 `#script:<名称>` 选脚本，脚本清单与写法见 `meridian-test-support/CLAUDE.md`
- 容量与内存压测（P7、P11）：`scripts/perf/capacity.sh turns|sse|rotate [--key=value]`，对运行中的 runtime（`run-runtime.sh --server.port=18080 --management.endpoints.web.exposure.include=health,metrics`）与带 `--scripts=scripts/perf/model-scripts` 的脚本化模型服务；场景与参数见 `docs/poc/P07-容量.md`
- 下载 AgentScope 2.0.4 源码包：`./mvnw -pl meridian-engine-agentscope -am compile dependency:sources -DincludeGroupIds=io.agentscope`

`test`、`verify` 不清理 `target/`：删除或改名测试类后，旧的 class 仍会被执行，先跑 `./mvnw clean`。IDE 的后台编译器也会把带编译错误的 class 写进 `target/`，Maven 的增量编译会沿用它们；测试报 `Unresolved compilation problems` 时同样先 `clean`。

环境脚本：

- `scripts/dev/up.sh`：启动本地 PostgreSQL（pgvector + zhparser）、Redis、MinIO 并等待健康、创建桶；首次启动构建 PostgreSQL 镜像。端口 15432、16379、19000/19001，连接信息与口令见 `deploy/compose/.env.example`（本地覆盖写 `.env`，不提交）。
- `scripts/dev/down.sh [--volumes]`：停止；加 `--volumes` 才删除数据。
- Compose `auth` profile（Keycloak 26.4，OIDC 的参考实现，ADR-0043）：`docker compose -f deploy/compose/compose.yaml --env-file deploy/compose/.env --profile auth up -d keycloak`，端口 18082，导入开发 realm `meridian-dev`（`deploy/keycloak/meridian-dev-realm.json`；容器内数据，重建即恢复）。用户 `dev-admin`（组 `meridian-platform-admins`，平台管理员）、`dev-developer`、`dev-viewer`，口令都是 `meridian-dev`；管理控制台 `http://localhost:18082/admin`（env 文件的 `KEYCLOAK_ADMIN_*`）。`scripts/dev/console-token.sh [用户]` 打印该用户的 access token（客户端 `meridian-dev-cli`）。
- `scripts/dev/run-control-plane.sh [Spring 参数]`：以 `dev` profile 在本机启动 control-plane（端口 18081）。管理 API 的令牌用 `console-token.sh`（需要 `auth` profile）；服务 JWT 的签名密钥写在 `deploy/compose/data/service-token-keys`，服务客户端密钥取 env 文件的 `MERIDIAN_*_CLIENT_SECRET`。
- `scripts/dev/run-runtime.sh [Spring 参数]`：以 `dev` profile 在本机启动 runtime（先 `up.sh` 与 `run-control-plane.sh`：API Key 经 control-plane 校验），健康检查 `/actuator/health`。
- 知识库（T18）：`scripts/dev/run-doc-parser.sh`（Docker 中的 doc-parser，端口 18084，镜像不存在时先构建）与 `scripts/dev/run-knowledge.sh`（端口 18083，Embedding 调 `http://localhost:18080` 的 runtime，所以 runtime 用 `--server.port=18080` 启动）；knowledge 的客户端密钥是 env 文件的 `MERIDIAN_KNOWLEDGE_CLIENT_SECRET`（`run-control-plane.sh`、`run-knowledge.sh` 先读 `.env.example` 再用 `.env` 覆盖，旧 `.env` 不必补）。
- `scripts/dev/dev-api-key.sh`：以 `dev-admin` 经管理 API 建开发租户（所有者也是 `dev-admin`）与项目、签发不限流的 API Key，写入 `deploy/compose/.env` 的 `MERIDIAN_DEV_API_KEY`（压测脚本读它）；四级预算设为不限（新租户默认零预算，`dev` 的 runtime 也读 control-plane 下发的限额，自己建的租户要经管理接口设置预算，ADR-0048）。
- 凭据与模型目录（T15a）：`dev` 的 runtime 默认读 `dev-config/providers`；`MERIDIAN_RUNTIME_CATALOG_SOURCE=control-plane scripts/dev/run-runtime.sh --server.port=18080` 改为经 control-plane 解析，`scripts/dev/catalog-smoke.sh [--dashscope]` 经管理 API 建凭据、供应商账号、价格表与模型并调用（逐项 PASS）。凭据库的开发主密钥在 `deploy/compose/data/secret-master-key`（丢了它库中的凭据就解不开）。
- 智能体配置（T15b）：`dev` 的 runtime 默认读 `dev-config/agents`（`dev-config/` 只用于测试与本地开发）；`MERIDIAN_RUNTIME_AGENT_CONFIG_SOURCE=control-plane` 改为经 control-plane 读取。`scripts/dev/agent-smoke.sh [--dashscope]` 经管理 API 建模型、环境、智能体并发布，再在 runtime 跑一轮会话（两个来源都切到 control-plane 的启动参数见脚本头部注释）。
- Compose `app` profile（control-plane、runtime、knowledge、doc-parser、脚本化模型服务与 Keycloak）：`scripts/dev/build-images.sh`（Jib 构建 Java 镜像，docker build 构建 doc-parser，含模型约 4 GB）后 `docker compose -f deploy/compose/compose.yaml --env-file deploy/compose/.env --profile app up -d`（没有 `.env` 时用 `.env.example`），端口 18081、18080、18083、18084、18090（`.env` 要有 `MERIDIAN_KNOWLEDGE_CLIENT_SECRET`，见 `.env.example`）；`scripts/dev/app-smoke.sh` 验证零预算被拒、设置预算后跑通一轮会话、`GET /v1/usage` 与审计查询看到这一轮、改配额、吊销 Key、控制台用户带 `Meridian-Project` 调公开 API 并逐项输出 PASS。
- kind 沙箱集群（集群名默认 `meridian`，上下文 `kind-meridian`），按顺序：
  1. `scripts/dev/kind-gvisor.sh meridian`：创建带 Calico（执行 NetworkPolicy）与 gVisor 运行时的集群。
  2. `scripts/dev/kind-verify.sh meridian`：验证 gVisor 生效、NetworkPolicy 被执行，输出 PASS。
  3. `scripts/dev/install-agent-sandbox.sh meridian`：安装 agent-sandbox v1.0.10 控制器与 CRD（校验清单摘要与镜像版本）。
  4. `scripts/dev/deploy-sandbox.sh meridian`：构建沙箱镜像、`kind load`、建出网代理的客户端密钥 Secret（取 env 文件的 `MERIDIAN_EGRESS_PROXY_CLIENT_SECRET`）、安装 chart（`values-kind.yaml`：出网代理回调宿主的 `host.docker.internal:18080`，向 `:18081` 的 control-plane 换取服务 JWT）、部署 P14 的目标服务 `deploy/kind/egress-echo.yaml`，等预热池就绪；改了镜像重跑它（会重建预热池中未认领的沙箱）。连 kind 的本地 runtime 用 `run-runtime.sh --server.port=18080`，同时要有 `run-control-plane.sh`。
- kind 的 Pod 网段是 `10.244.0.0/16`：不能与 Docker Desktop 的 `host.docker.internal`（`192.168.65.254`）重叠，否则 Pod 访问不到宿主上的 runtime；T12b 之前建的集群（`192.168.0.0/16`）要删掉重建。
- `kind delete cluster --name meridian`：删除集群，释放内存（T28、T29 还会用到它，删除后按上面的顺序重建）。

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
- 仓储继承 `TenantScopedRepository`；豁免只限架构基线"租户隔离"一节列出的端口实现类。子类只能经 `sql(...)` 写 SQL，语句必须引用 `:tenant_id`（项目级还要 `:project_id`），值由基类从 `PlatformContext` 绑定；跨租户的继承 `SystemRepository`，放在 `infra.system` 并标注 `@CrossTenant`。runtime 中所有 JDBC 经 `common.concurrent.Blocking`；阻塞的流与工具执行 `subscribeOn(Blocking.scheduler())`，不用 `Schedulers.boundedElastic()`（线程上限 10×CPU，曾把单节点的模型调用并发限在 40，P7）。
- 禁止依赖：Spring AI、LangChain4j、AgentScope 的 Spring Boot starter、RAG 扩展、Service 模块。
- Java 包根与 Maven groupId：`cn.refinex.meridian`。依赖版本只在 `meridian-bom` 声明。不直观的模块包根：`engine-spi` → `engine.spi`，`engine-agentscope-test` → `engine.agentscope.testkit`，`model-client` → `modelclient`，`control-plane` → `controlplane`，`test-support` → `testsupport`；不在已登记包根下的类会被 ArchUnit 拒绝。
- 身份只有三种（ADR-0044）：公开 API 是 API Key 或 OIDC 令牌加 `Meridian-Project`；管理 API 只接受 OIDC 令牌；`/internal/` 只接受 control-plane 签发的服务 JWT，处理器首行按调用方服务名限定（`ServiceCredentialAuthorization.require(exchange, 调用方…)`）。不再有开发期的静态服务凭据或控制台令牌，测试用 `meridian-test-support` 的 `KeycloakRealm`、`TestTokenIssuer`。
- 管理 API（`/admin/v1/`，`meridian-admin-v1.yaml`）的授权只经 `security.rbac`：处理器标注 `@RequiresPermission`，角色矩阵只在 `RoleMatrix`，业务代码不写角色判断；用例自己绑定 `PlatformContext`（ADR-0042）。
- 依赖规则由构建强制：maven-enforcer 默认拒绝平台模块与 AgentScope 构件，每个模块在自己 POM 的 `enforce-rules` 中按「模块依赖规则」放行（ADR-0022）；包层面由 `meridian-arch-tests` 的 ArchUnit 规则检查，模块表在 `MeridianModule`。

## 集成 AgentScope 时最容易写错的地方

这些都已在 AgentScope `v2.0.4` 源码核实，细节见架构基线"运行时架构"一节：

- 状态存储用平台自研的 `FencedAgentStateStore`，每次写入在同一事务里校验租约。失去租约时只能取消订阅，禁止调用 `interrupt`，因为中断会触发 AgentScope 写状态。
- 崩溃恢复按"本轮是否写过状态内容"判定（`agent_states.content_turn_id`），不能只比较 `content_epoch`：`lease_epoch` 每轮从 1 开始（ADR-0028）。
- 每轮开始前、结束后（在 `finally` 中）都要 `clearStateCache(userId, threadId)`，再用 `getDelegate().replacePermissionContext(...)` 安装合成后的权限。
- `RuntimeContext.sessionId` 是线程 ID（`thr_…`），不是会话 ID。命名空间等会话级的值从 `PlatformContext` 取。
- 子线程上下文必须用 `RuntimeContext.builder()` 从空白构建，禁止 `builder(parent)`。子线程的终态不能写成 `session.status_*`（会推进会话状态），只写 `session.thread_status_idle`；子线程的工具调用记在父轮次下，父线程恢复时只看本线程的调用（ADR-0040）。
- 一轮的输入是四类 sealed `TurnInput` 之一，审批结果和自定义工具结果不能与普通消息混在同一轮。
- 显式 ask 规则在 `DONT_ASK` 下仍返回 ask，无人审批的执行（`unattended`、子线程）要在合成时把 ask 改写为 deny；平台安全规则让权限上下文永远不"平凡"，`DEFAULT` 模式下没有命中 allow 的工具都要确认，智能体必须显式放行工具（ADR-0031）。
- `HarnessAgent` 的装配清单（`disableWebTools`、`disableToolsConfig`、`disableSubagents`、`disableTranscript`、`maxRetries(1)`、不设 `fallbackModel`、`NoopAsyncToolRegistry`、`disableAtPathExpansion`、工具执行超时 660 秒等）以"引擎装配"表为准，由 `AgentAssemblerTest` 逐项断言。`maxRetries` 是含首次的总尝试次数，0 会抛异常（ADR-0023）；2.0.4 的 `@路径` 展开绕过权限、Toolkit 默认 5 分钟工具超时截断 600 秒的 `bash` 与子智能体，都要在装配时关掉或放宽（ADR-0041）。
- 平台工具继承 `PlatformTool`（它继承 `ToolBase`、实现 `matchRule`、记账与幂等键），新增工具的步骤见 `meridian-engine-agentscope/CLAUDE.md`；文件和命令操作经 `call.filesystem()` 并传入 `call.runtimeContext()`。
- 工作区命名空间 `[tenant, project, end_user, session]` 取自 `PlatformContext`（含 `endUserId`），不取 `RuntimeContext.getSessionId()`（线程 ID）。`wait_async_results` 在 2.0.4 中是 `ToolBase`；拒绝工具用权限规则，`onActing` 中间件合成的事件不会被发布（ADR-0034）。
- Anthropic、Gemini 不用 2.0.4 的 `AnthropicChatModel`、`GeminiChatModel`（凭据只能构造时给、SDK 自带重试、流在 `boundedElastic` 上），用平台适配器复用它们的 formatter 与解析器（ADR-0046）。
- 限流、预算、计量、重试与降级在 `RoutedModel` 内完成；压缩器会吞掉模型异常，由 `CompactionGuardMiddleware` 兜住：`CompactionMiddleware` 在调用下游之前就把摘要写进状态，失败时内层先恢复上下文再抛异常（ADR-0032）。
- 系统提示词只由 `PlatformPrompt.compose` 组装，装配时关闭 `WorkspaceContextMiddleware`；时间等动态内容只能以 `<platform_context>` 消息注入，否则同一智能体的请求前缀不同、命中不了提示词缓存。外部内容（网页、搜索、知识库、MCP）交给模型前用 `SourceTags.wrap` 包标签（ADR-0035）。
- MCP 客户端用 `McpClientBuilder` 先选传输再设定制器，构建异步客户端；凭据只放进单次调用的 Reactor Context，同步客户端会丢掉它（ADR-0035）。
- `sandbox` 档每轮必须把会话沙箱作为外部 `SandboxContext` 注入：缺少时 harness 会用装配时的默认上下文自建沙箱（平台用一律拒绝的 `PlatformManagedSandboxClient` 防止）；`SandboxBackedFilesystem` 没有本次调用的绑定时退回共享实例上最后绑定的沙箱，平台工具必须经 `SandboxWorkspaceFilesystem`（路径翻译到 `/workspace`、无绑定即拒绝）。权限引擎对 allow、ask、deny 都调同一个 `matchRule`，`bash` 复合命令的拒绝与询问"任一段命中"在 `BashTool.checkPermissions` 中补上（ADR-0037）。
- `AgentEventType`（31 个）和 `GenerateReason` 的映射必须穷举，由单元测试保证。

## 测试

测试类名决定由谁运行：`*Test` 由 Surefire 跑，`*IT` 由 Failsafe 在 `verify` 阶段跑，`*LiveIT` 必须带 JUnit 标签 `live`，只在 `-Plive` 时运行；不符合命名的测试不会被执行。

所有智能体行为必须能用 `ScriptedModel`（在 `meridian-engine-agentscope-test`）这个脚本化假模型跑通，测试不依赖真实模型；`meridian-engine-agentscope` 的装配与场景测试也放在该模块（反向依赖会成环），它可以依赖 common 与 engine-spi（ADR-0025）。集成测试使用 Testcontainers（PostgreSQL、Redis、MinIO）；runtime 测试中开启 BlockHound。
