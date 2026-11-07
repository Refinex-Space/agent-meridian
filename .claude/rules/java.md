---
paths:
  - "**/*.java"
  - "**/pom.xml"
---

# Java 编码规则（精要）

完整规范：`docs/engineering/coding-standards.md`。架构约束：`docs/architecture/architecture.md`。

- 包结构：`cn.refinex.meridian.<模块>.<上下文>.<分层>`；应用内分 `api` / `app` / `domain` / `infra`；`domain` 不依赖 Spring 与 `infra`；`api` 只做 decode → call → map。
- 只有 `meridian-engine-agentscope`（及其测试模块）能引用 `io.agentscope`；runtime 只在 `config` 包引用其装配入口。
- 写工具类、基础类、客户端前，先查编码规范「通用能力复用」表，复用唯一实现，不就地另写。
- 注释用中文：公开类型、公开方法、record 组件、字段、枚举常量、常量都要有；写"是什么、为什么、约束"，引用架构设计章节名；不写复述代码的注释、不写 `@author`、不留无归属的 TODO（写成 `TODO(Txx)`）。只给自己写或改的代码加注释。
- 数据访问用 `JdbcClient`：业务仓储继承 `TenantScopedRepository`（项目级或租户级），跨租户后台作业继承 `SystemRepository`（只在 `infra.system`，带 `@CrossTenant`）；runtime 中的阻塞调用经 `Blocking` 调度。
- 时间用注入的 `Clock`；架构规定的数值写成常量或配置默认值并注明章节。
- 依赖版本只在 `meridian-bom` 声明；禁止 Spring AI、LangChain4j、AgentScope 的 starter、RAG 扩展、Service 模块。
- AgentScope 只以 2.0.4 为准，读 sources jar 或 `git show v2.0.4:<路径>`，不读参考仓库的工作区。
- 测试：单元 `*Test`、集成 `*IT`、真实模型 `*LiveIT`（`live` 标签）、kind `kind` 标签；智能体行为用 `ScriptedModel`；不削弱断言、不硬编码。
