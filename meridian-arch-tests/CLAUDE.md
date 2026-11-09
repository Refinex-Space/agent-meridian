# meridian-arch-tests

全仓库的 ArchUnit 规则（架构设计「防漂移机制」）：依赖本仓库全部生产模块，只分析生产代码，规则在 `verify` 中运行。不检查 Maven 依赖（各模块 POM 的 maven-enforcer，ADR-0022），也不检查运行时行为。

## 命令

- 架构测试：`./mvnw -pl meridian-arch-tests -am verify`
- 单条规则：`./mvnw -pl meridian-arch-tests -am verify -Dtest=ArchitectureRulesTest`

## 包地图

- `ArchitectureRulesTest`：规则 1 至 13，每条一个 `@ArchTest` 字段，`as(...)` 写"规则 N：…"，`because(...)` 指向架构设计章节或 ADR；规则编号是文档与报告引用的名字，新增规则取下一个编号，不复用。类注释列出「防漂移机制」中由别的手段（enforcer、装配自检、契约测试）落地的条目。
- `MeridianModule`：模块与包根的对照表、「模块依赖规则」表（规则 3 用它检查包依赖）。新增模块或改依赖方向时同步修改它与架构设计。

## 不变量

- 只导入生产代码（`ImportOption.DoNotIncludeTests`）：测试夹具与测试类不受业务规则约束（例如 runtime 的 `config` 测试可以直接引用 AgentScope）。
- 规则都 `allowEmptyShould(true)`：空模块（control-plane、knowledge、model-client 在 M1 之前）也能通过。
- 不在已登记包根下的类会被规则 3 拒绝：新模块的包根先登记到 `MeridianModule`。

## 易错点

- 本模块的 POM 依赖了全部生产模块：新增模块要加进 POM，否则它的类不会被分析，规则对它静默通过。
- `noClasses().should().dependOnClassesThat()` 检查的是字节码中的依赖：只在 Javadoc 中引用的类不算依赖。

## 对外接口

无。被 `scripts/dev/verify.sh` 与 CI 运行。
