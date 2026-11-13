# 测试规范与可用能力

本规范规定测试怎么分层、怎么命名、用什么夹具，以及本机已经核实可用的测试手段。验证一项功能"完成"，必须拿出可复现的证据：命令加实际输出、测试报告、截图。

## 测试分层

| 层 | 命名 | 运行方式 | 依赖 |
|---|---|---|---|
| 单元测试 | `*Test` | Surefire，默认运行 | 无外部依赖 |
| 集成测试 | `*IT` | Failsafe，`verify` 阶段运行 | Docker（Testcontainers） |
| 真实模型测试 | `*LiveIT`，JUnit 标签 `live` | 默认不运行；`-Plive` 时运行 | `DASHSCOPE_API_KEY` |
| kind 集群测试 | `*KindIT`，JUnit 标签 `kind` | 默认不运行（CI 没有 kind）；`-Pkind` 时只运行它们 | 本机 kind 集群 `meridian` 与已部署的沙箱（根 `CLAUDE.md`「环境脚本」） |
| Python 子项目 | `tests/test_*.py` | pytest，`scripts/dev/verify.sh` 运行 | uv |
| 智能体场景测试 | `*ScenarioTest` | 默认运行 | `ScriptedModel`，不依赖真实模型 |
| 契约测试 | `*ContractIT`；每个服务最后运行的 `ContractCoverageIT` | 默认运行 | OpenAPI 规范；`meridian-test-support` 的 `contract.ContractCoverage` 按真实路径记录被校验的操作（R2） |
| 端到端测试 | `e2e/` 下的脚本或 `*E2EIT`（M1 验收场景的后端预演：`e2e/backend/`） | 按任务说明运行 | 本地基础设施与服务 |
| 前端单元与组件 | `*.test.ts(x)` | Vitest | 无 |
| 前端端到端 | `console/e2e/*.spec.ts` | Playwright | 运行中的控制台与后端 |

Maven 命令见根目录与各模块的 `CLAUDE.md`；pnpm 命令由 T21 落地后写入。

## 测试的写法

- 测试验证行为，不验证实现细节。断言写到能区分"对"和"错"的程度：断言事件序列、状态迁移、数据库行，而不是只断言"没有抛异常"。
- 修 Bug 先写能复现它的失败测试，再修复。
- 测试数据就近构造，用构建器或工厂方法，不依赖测试之间的执行顺序。
- 并发与时序：用可控时钟（注入 `Clock`）和显式的同步点，不用 `Thread.sleep` 等待"差不多够了"的时间。
- **禁止**为了让测试通过而削弱断言、删除测试、加 `@Disabled`、硬编码只对测试输入成立的逻辑。测试本身确实写错了，在提交说明和任务报告里写清楚理由再改。

## 智能体行为测试

所有智能体行为必须能用 `ScriptedModel` 跑通。它实现 AgentScope 的 `Model` 接口，按脚本依次返回文本、工具调用、结构化输出、错误，并记录收到的每次请求（消息、工具 schema、参数），供断言使用。

场景测试覆盖的典型路径：纯文本回答、单工具调用、并行工具调用、权限 ask 与确认续跑、自定义工具挂起与回传、首包前错误、首包后错误、中断、steer 注入、压缩触发、子智能体、恢复轮。

## 真实模型测试

本机已配置环境变量 `DASHSCOPE_API_KEY`，2026-11-07 实测以下端点可用：

| 用途 | 端点 | 模型 | 实测结果 |
|---|---|---|---|
| 对话、工具调用、流式 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-plus` | 正常；工具调用返回 `tool_calls`；流式带 `usage` |
| 低成本对话 | 同上 | `qwen3.7-flash` | 正常；流式增量里带 `reasoning_content`（思考内容） |
| Embedding | `https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings` | `qwen3.7-text-embedding` | 1024 维 |
| rerank（兼容格式） | `https://dashscope.aliyuncs.com/compatible-api/v1/reranks` | `qwen3.7-text-rerank` | 返回 `results[].index` 与 `relevance_score` |
| rerank（原生格式） | `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` | `qwen3.7-text-rerank` | 返回 `output.results` |

规则：

- 模型名不写死在测试代码里，从环境变量读取，并给出默认值：`MERIDIAN_LIVE_CHAT_MODEL`（默认 `qwen-plus`）、`MERIDIAN_LIVE_EMBEDDING_MODEL`（默认 `qwen3.7-text-embedding`）、`MERIDIAN_LIVE_RERANK_MODEL`（默认 `qwen3.7-text-rerank`）。
- 没有设置 `DASHSCOPE_API_KEY` 时，测试用 `Assumptions.assumeTrue` 跳过，不失败。
- **禁止**在日志、断言消息、测试报告、提交内容里输出密钥本身。
- 控制成本：`max_tokens` 设到够用的最小值，单个测试类的真实调用不超过 20 次。
- 真实模型的输出不确定，断言写结构性条件（返回了工具调用、工具名正确、参数可解析、文本非空），不断言具体措辞。

## 集成测试夹具

`meridian-test-support` 提供以单例方式复用的容器（`MeridianContainers`），同一个测试 JVM 内共用，JVM 退出后由 Testcontainers 回收：

- PostgreSQL 17：使用 `deploy/docker/postgres/` 构建的镜像，内含 pgvector 与 zhparser，初始化脚本与本地 compose 相同（`meridian_migrator`、`meridian_app` 两个角色，`cp`、`rt`、`kb` 三个 schema）。镜像标签带构建目录的内容摘要，目录不变时复用本机镜像；首次构建约 2 分钟，需要代理时设置 `MERIDIAN_DOCKER_BUILD_PROXY`。
- Redis 7.4。
- MinIO：官方镜像已停止发布，使用按摘要固定的 Chainguard 镜像（ADR-0020），桶 `meridian-test` 已创建。

辅助：

- `TestDatabase`：两个角色的数据源（被测代码用只有 DML 的 `meridian_app`）、`migrate(schema)`（按 `classpath:db/migration/<schema>` 约定）、`runScript(schema, 资源)`、`truncate(schema)`。
- `SpringContainerProperties`：在 `@DynamicPropertySource` 中注册数据源、Flyway、Redis、对象存储属性。
- 身份（ADR-0044）：`auth.KeycloakRealm` 是以单例复用的 Keycloak（Testcontainers 的 Keycloak 模块，导入 `deploy/keycloak/meridian-dev-realm.json`），`user(名字, 组…)` 经管理 REST API 建用户、`token(名字)` 取 OIDC 令牌，`issuer()` 配给被测服务；`auth.TestTokenIssuer` 是不需要 Docker 的令牌签发方（JWKS、发现文档、`/oauth2/token`），签发服务 JWT 或任意声明的令牌，`route(...)` 可以在它上面挂替身接口。服务 JWT 与控制台身份都走真实的校验，不用开发期的静态凭据。

容器跨测试类共用，数据也会保留：测试用随机 ID 隔离数据，或开始前清空自己用到的 schema。

运行集成测试需要 Docker Desktop 处于运行状态。测试开始前用 `docker info` 检查，未运行时直接报告阻塞，不要尝试绕过。

BlockHound 需要 JVM 参数 `-XX:+AllowRedefinitionToAddDeleteMethods -XX:+EnableDynamicAgentLoading`（`meridian-persistence` 的 Failsafe 已配置）。安装后在 Reactor 线程上写日志也会被拦截，断言与日志放在测试线程上做。

## 脚本化模型服务

`ScriptedModel` 只在测试的 classpath 上，起着的 runtime 只能接真实的供应商；端到端测试、控制台的 Playwright 用例、场景预演与 M1 验收默认用 `meridian-test-support` 的 `ScriptedOpenAiServer`（ADR-0033），只有 `live` 用例走 DashScope：

- 它是 OpenAI 兼容的 HTTP 服务（对话补全的流式与非流式、确定的 Embedding、DashScope 格式的 rerank），runtime 把它当普通供应商调用，限流、预扣、计量都照常经过。
- 测试中内嵌：`ScriptedOpenAiServer.builder().start()`，把 `baseUrl()` 写进临时的供应商目录（`meridian.runtime.model-routing.providers-directory`）；独立启动：`scripts/dev/scripted-model.sh`（默认端口 18090），`dev` profile 的 runtime 经 `dev-config/providers/scripted.yaml` 调用它（模型 `mdl_dev_scripted`）。
- 用户消息以 `#script:<名称>` 开头选择脚本，没有标记时回显；内置脚本清单与 YAML 写法见 `meridian-test-support/CLAUDE.md`。新的端到端情形优先加脚本，不为它接真实模型。

## 前端测试


- **Vitest 加 Testing Library**：组件与 Hook 的行为测试。
- **Storybook**：每个通用组件都有 story，覆盖所有状态（默认、悬停、聚焦、禁用、加载、空、错误）；启用无障碍检查插件。
- **Playwright**：关键用户路径的端到端测试，在亮色和暗色两种主题下截图。
- **ego-browser 技能**：由 AI 驱动浏览器做探索式走查，用于设计验收。每个页面检查：布局与对齐、加载骨架、空态、错误态、键盘操作、亮暗主题、`prefers-reduced-motion`、窗口宽度 1280 与 1920。截图保存在 `tmp/evidence/<任务编号>/`（已被忽略），任务报告中列出检查结果。
- **frontend-design 技能**：在做视觉决策和页面设计时使用。

## 本机环境

2026-11-07 安装并核实的本机环境：

| 项 | 状态 | 说明 |
|---|---|---|
| macOS arm64，Docker Desktop 29.4 | 可用，分配内存 8 GB | 同时运行 PostgreSQL、Redis、MinIO、Keycloak、Langfuse 与 kind 集群时内存吃紧。需要整套环境的任务开始前，先提醒用户关闭不用的应用，并把 Docker Desktop 内存调到 12 GB 以上；不需要的组件不启动 |
| JDK 25 | Temurin 25.0.5+8 LTS，位于 `~/develop/jdk/temurin25/Contents/Home` | 已写入 `.claude/settings.local.json` 的 `JAVA_HOME`，智能体会话自动使用。用户自己的终端需要 `export JAVA_HOME=$HOME/develop/jdk/temurin25/Contents/Home` |
| Maven 3.9.12 | 可用 | 项目使用 Maven Wrapper；Maven Central 可直连 |
| Node 24、pnpm 10、npm 11 | 可用 | npm 仓库可直连 |
| Python 3.14、uv 0.11 | 可用 | PyPI 可直连；Python 子项目用 uv 管理独立的 Python 版本 |
| kubectl 1.34、kind 0.33、helm 4.3 | 可用 | — |
| gVisor | **本机可用** | `scripts/dev/kind-gvisor.sh` 在 kind 节点的 containerd 中注册 runsc（release-20261102），Pod 内核显示 `4.19.0-gvisor` |
| NetworkPolicy | **本机可用** | kind 默认的 kindnet 不执行 NetworkPolicy（实测）；上面的脚本改用 Calico v3.33.0，`scripts/dev/kind-verify.sh` 实测拒绝全部出网后连接被阻断 |
| 下载代理 | `MERIDIAN_DOWNLOAD_PROXY=http://127.0.0.1:7897` | 已写入本地配置。GitHub 发布物、gVisor 等慢速下载时使用；Maven、npm、PyPI 直连即可 |
| `DASHSCOPE_API_KEY` | 已设置 | 见"真实模型测试" |
| DNS | fake-ip 方式的透明代理，公网域名解析到 `198.18.0.0/15` | 供应商端点的地址策略默认拒绝这个保留段（ADR-0045）：两个服务的 `dev` 配置与 `ControlPlaneCatalogLiveIT` 显式放行它，其他环境不要照抄 |
| GitHub CLI | 可用 | 不用于推送 |

### 代理的两个坑

- Claude Code 工具沙箱会给 Bash 注入只在宿主回环地址可用的代理变量。凡是把环境变量传进容器或虚拟机的命令（`kind create`、`docker run -e`），必须显式指定代理或去掉这些变量，否则容器内的网络请求会失败。`kind-gvisor.sh` 已按此处理。
- **禁止**在命令输出中打印代理变量、`~/.npmrc`、`~/.m2/settings*.xml` 等可能含凭据的内容。

## 证据要求

任务报告中的每一条"已验证"都要附证据：

- 命令验证：写出命令与关键输出（测试数量、通过数、失败数）。
- 性能与容量：写出测试条件（数据量、并发、机器）与测得的数值。
- 界面验证：截图路径与检查结论。
- 无法在本机验证的项：明确写"未验证"和原因，不能写"应该可以"。
