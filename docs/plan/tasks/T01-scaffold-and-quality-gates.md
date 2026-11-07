# T01 工程脚手架与质量门禁

| 项 | 内容 |
|---|---|
| 阶段 | 0 工程基座 |
| 依赖 | 无 |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 中 |
| 关联验证项 | P1（依赖解析部分） |

启动方式：在仓库根目录开新会话，输入 `/task T01`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

仓库目前只有文档。本任务搭建 Maven 多模块骨架，并把架构设计"防漂移机制"中能自动化的约束全部变成构建失败条件。之后每个任务都在这套门禁下工作，所以它必须一次做对：门禁漏掉的东西，后面会悄悄漂移。

完成后：`scripts/dev/verify.sh` 一条命令跑完格式、编译、测试、架构规则、依赖规则；违反任何一条规则，构建失败并给出可读的原因。

## 必读

1. 架构设计 `docs/architecture/architecture.md`：「技术基线」「代码仓库与模块」（含模块依赖规则、端口、包结构）「防漂移机制」「测试策略」。
2. `docs/engineering/coding-standards.md` 全文。
3. `docs/engineering/testing.md`：「测试分层」「本机环境」。
4. `docs/engineering/claude-md.md` 全文。

## 范围

### 要做

- Maven Wrapper 与根 POM、BOM、全部 Java 模块的骨架。
- 构建插件：编译、Spotless、Checkstyle、maven-enforcer、Surefire、Failsafe、JaCoCo，以及 `live` profile。
- `meridian-arch-tests` 模块与第一批 ArchUnit 规则。
- 检查脚本、编辑器与 git 属性文件、GitHub Actions CI。
- 更新根目录 `CLAUDE.md` 的"命令"一节。

### 不做

- 任何业务代码、OpenAPI 规范、数据库迁移、Docker Compose（T02 起）。
- `meridian-sdk-java`（T26 创建）。
- 控制台与 Python 子项目（各自的任务创建）。

## 交付物

### 1. JDK 与 Maven

- 本机 JDK 25 已安装在 `~/develop/jdk/temurin25/Contents/Home`，并已通过 `.claude/settings.local.json` 设为会话的 `JAVA_HOME`。先确认 `java -version` 与 `./mvnw -v` 都是 25；不是的话检查 `JAVA_HOME`，不要改用其他 JDK。
- Maven Wrapper 使用 only-script 模式，Maven 取 3.9 系列最新版。

### 2. 根 POM 与模块

- 根 POM：`groupId` 为 `cn.refinex.meridian`，`artifactId` 为 `meridian-parent`，版本 `0.1.0-SNAPSHOT`，`packaging` 为 `pom`。
- 模块（与架构设计「仓库布局」一致，暂不含 `meridian-sdk-java`）：`meridian-bom`、`meridian-common`、`meridian-api`、`meridian-security`、`meridian-persistence`、`meridian-engine-spi`、`meridian-engine-agentscope`、`meridian-engine-agentscope-test`、`meridian-model-client`、`meridian-control-plane`、`meridian-runtime`、`meridian-knowledge`、`meridian-test-support`、`meridian-arch-tests`。
- 每个 Java 模块只有 POM 和包根的 `package-info.java`。包根按编码规范「包与目录规划」确定，例如 `cn.refinex.meridian.runtime`、`cn.refinex.meridian.engine.agentscope`。`package-info.java` 用中文 Javadoc 写明模块职责、不负责什么、允许依赖哪些模块（照抄架构设计的依赖规则）。
- 模块之间的 `<dependency>` 按「模块依赖规则」表声明。`meridian-engine-agentscope` 依赖 `io.agentscope:agentscope-core`、`agentscope-harness`、`agentscope-extensions-sandbox-kubernetes`（版本 2.0.4），用于在本任务中确认依赖可以解析。

### 3. BOM

- `meridian-bom` 导入 Spring Boot 4.1.2 的依赖管理，声明 AgentScope 2.0.4 的上述构件。**不要**导入 AgentScope 自己的 `agentscope-dependencies-bom`，它锁定的是 Spring Boot 4.0.4。
- Jackson 2（`com.fasterxml.jackson`）的版本必须满足 AgentScope 2.0.4 的需要（它使用 2.21.x）；Jackson 3（`tools.jackson`）由 Spring Boot 管理。用 `./mvnw -pl meridian-engine-agentscope dependency:tree` 确认两者共存，版本符合预期。
- MCP Java SDK 解析为 0.17.2（随 AgentScope）。
- 本任务和 T02 需要的测试与工具依赖：JUnit 5、AssertJ、Mockito、ArchUnit、Testcontainers、Reactor Test、BlockHound、ULID（`com.github.f4b6a3:ulid-creator`）。版本取最新稳定版、发布至少两周，在 BOM 中集中声明。其余依赖由用到它的任务加入。

### 4. 构建插件（在根 POM 的 `pluginManagement` 与 `plugins` 中统一配置）

- **编译**：`release` 25，开启 `-parameters`。
- **Spotless**：Java 用 palantir-java-format，移除未用导入，固定导入顺序；POM 用 sortPom。`verify` 阶段执行 `check`。
- **Checkstyle**：配置放在 `build/checkstyle/checkstyle.xml`，只针对 `src/main`。规则：禁止通配符导入；`public` 与 `protected` 的类型必须有 Javadoc；`public` 方法必须有 Javadoc（record 的访问器、`@Override` 方法除外）；禁止 `System.out`、`System.err`、`printStackTrace`；禁止 `@author` 标签。违规导致构建失败。
- **maven-enforcer**：
  - `requireJavaVersion` 为 `[25,26)`，`requireMavenVersion` 为 `[3.9,)`。
  - `bannedDependencies`（含传递依赖）在所有模块禁止：`org.springframework.ai:*`、`dev.langchain4j:*`、`io.agentscope:*-spring-boot-starter*`、`io.agentscope:agentscope-extensions-rag*`、`io.agentscope:agentscope-service*`。
  - 在除 `meridian-engine-agentscope`、`meridian-engine-agentscope-test`、`meridian-runtime`、`meridian-arch-tests` 之外的模块，额外禁止 `io.agentscope:*`。
  - `banDuplicatePomDependencyVersions`。
- **Surefire**：运行 `*Test`，排除 JUnit 标签 `live`。**Failsafe**：运行 `*IT`，同样排除 `live`。
- **profile `live`**：只运行带 `live` 标签的测试（`*LiveIT`）。
- **JaCoCo**：生成报告；对每个模块的 `**/domain/**` 设置行覆盖率不低于 80% 的检查；没有 domain 代码时检查自然通过。

### 5. 架构测试 `meridian-arch-tests`

- 以 test 作用域依赖所有业务模块，用 ArchUnit 导入 `cn.refinex.meridian..` 下的类。
- 第一批规则（`allowEmptyShould(true)`，空代码时通过）：
  1. 只有 `cn.refinex.meridian.engine.agentscope..` 可以依赖 `io.agentscope..`（`engine-agentscope-test` 的测试夹具包也在其中）。
  2. `cn.refinex.meridian.runtime..` 中只有 `cn.refinex.meridian.runtime.config..` 可以依赖 `cn.refinex.meridian.engine.agentscope..`。
  3. 按「模块依赖规则」表，每个模块的包不依赖表中未允许的其他模块的包；三个应用的包互不依赖。
  4. `..domain..` 不依赖 `org.springframework..` 与 `..infra..`。
  5. `..api..` 不依赖 `..infra..`。
  6. 任何类不依赖 `org.springframework.ai..`、`dev.langchain4j..`。
  7. `meridian-common` 中只有 `..common.context..` 与 `..common.concurrent..` 可以依赖 `reactor..`。
- 规则与架构设计「防漂移机制」表逐条对应。本任务无法落地的规则（例如 `TenantScopedRepository` 尚不存在），在测试类中用注释写明由哪个任务落地，并在 `PLAN.md` 中登记。

### 6. 脚本

- `scripts/dev/verify.sh`：`./mvnw -B -ntp spotless:check verify` 加 `scripts/ci/check-pom-versions.sh`。失败时退出码非 0。后续任务会往里追加前端与 Python 的检查。
- `scripts/ci/check-pom-versions.sh`：检查除 `meridian-bom` 与根 POM 的 `pluginManagement`、`dependencyManagement` 之外，所有 POM 的 `<dependency>` 都不写 `<version>`。

### 7. 其他文件

- `.editorconfig`（UTF-8、LF、Java 4 空格缩进、YAML 与 JSON 2 空格）。
- `.gitattributes`（文本文件规范换行，`mvnw` 保持 LF 且可执行）。
- `.github/workflows/ci.yml`：`ubuntu-latest`，`actions/setup-java` 安装 Temurin 25，缓存 Maven，运行 `scripts/dev/verify.sh`。
- `.claude/settings.json` 已存在，按需追加本任务新用到的、安全的命令到 `allow`；不放宽 `deny`。

### 8. CLAUDE.md

- 根目录 `CLAUDE.md` 的"命令"一节写入实际执行过的命令：全量检查、单模块测试、单个测试类、只跑集成测试、真实模型测试、格式化修复、依赖树。
- 如果 `init` 技能可用，调用它获取改进建议，逐条核对后再决定是否采纳。

## 实现要点

- **先跑通最小骨架，再加门禁**：先确认空模块能编译，再逐个加入插件，每加一个就跑一次，避免多个问题叠在一起。
- enforcer 的模块级差异配置写在各模块 POM 的插件 `configuration` 中，复用根 POM 的执行定义，不复制整段配置。
- Checkstyle 的 Javadoc 规则只要求"存在"；中文内容与质量由代码评审保证。
- ArchUnit 用 `@AnalyzeClasses` 加 `ImportOption.DoNotIncludeTests`，测试代码不受业务规则约束。

## 验收标准

- [T01-A01] `java -version` 与 `./mvnw -v` 显示 Java 25 与 Maven 3.9.x。验证：两条命令的输出。
- [T01-A02] 干净检出上 `scripts/dev/verify.sh` 通过，全部模块 BUILD SUCCESS。验证：命令输出的模块列表与结果。
- [T01-A03] Spotless 生效：在任一 `package-info.java` 中故意破坏格式，`verify.sh` 失败并指出文件；恢复后通过。验证：两次运行的关键输出。
- [T01-A04] Checkstyle 生效：临时添加一个没有 Javadoc 的 `public` 类，构建失败并指出规则；删除后通过。验证：关键输出。
- [T01-A05] enforcer 生效：临时让 `meridian-common` 依赖 `org.springframework.ai:spring-ai-commons`、让 `meridian-control-plane` 依赖 `io.agentscope:agentscope-core`，两次构建都失败并指向 `bannedDependencies`；撤销后通过。验证：关键输出。
- [T01-A06] ArchUnit 生效：临时在 `meridian-common` 中写一个引用 `io.agentscope` 类型的类（同时临时放开 enforcer 以便编译），架构测试失败并指出规则 1；撤销后通过。验证：关键输出。
- [T01-A07] `check-pom-versions.sh` 能发现子模块依赖中的 `<version>`。验证：临时加入一个版本号后脚本失败，撤销后通过。
- [T01-A08] `dependency:tree` 中 Jackson 2 与 Jackson 3 共存、AgentScope 为 2.0.4、MCP SDK 为 0.17.2、Spring Boot 为 4.1.2。验证：树中对应行。
- [T01-A09] 每个 Java 模块都有包根 `package-info.java`，中文 Javadoc 写明职责与允许的依赖。验证：列出文件清单。
- [T01-A10] `ci.yml` 语法有效（有 actionlint 就用 actionlint，否则用 YAML 解析器校验）。验证：命令输出。
- [T01-A11] 根目录 `CLAUDE.md` 的"命令"一节已更新，其中每条命令都实际执行成功过。验证：在报告中列出命令与结果。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 把临时制造的违规全部撤销，确认 `git status` 只包含本任务的正式产物。
- 在 `PLAN.md` 中登记本任务未能落地、由后续任务落地的架构规则。
