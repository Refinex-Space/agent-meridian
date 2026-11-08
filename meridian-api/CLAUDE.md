# meridian-api

公开 API 的 OpenAPI 3.1 规范与由它生成的 DTO，是三个应用共用的契约。不含服务端接口：各应用从同一份规范生成自己的接口（runtime 为 reactive，control-plane 与 knowledge 为 servlet）。生成链与约束的来由见 ADR-0029。

## 命令

- 生成 DTO、编译与单测：`./mvnw -pl meridian-api -am test`
- 规范校验（3.1 解析、端点与事件类型对照、写作约束）：`./mvnw -pl meridian-api -am test -Dtest=OpenApiSpecTest -Dsurefire.failIfNoSpecifiedTests=false`
- 兼容性检查（与上一个已提交版本比较）：同上，`-Dtest=OpenApiCompatibilityTest`。基线取自 git：工作区的规范与最近一次改动它的提交不同时与那次提交比较，相同时与它的父提交比较；没有基线（首个版本、浅克隆）时跳过
- 改了规范后跑 runtime 的契约测试：`./mvnw -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='*ContractIT' -Dfailsafe.failIfNoSpecifiedTests=false`
- 兼容性检查的基线是最近一次改动规范的提交：同一任务里多次改规范，后一次改了前一次新增、尚未发布的字段类型时会被拒绝，把改动并入那次提交（未推送时 `git commit --amend`）。
- 生成器不清理输出目录：删除或改名 schema 后先 `./mvnw -q -pl meridian-api clean`，否则旧类残留，可能编译失败（"无效的 permits 子句"）。

## 包地图

- 规范：`src/main/resources/openapi/meridian-v1.yaml`（随 jar 发布，契约测试从类路径读取）；内部接口 `meridian-internal-v1.yaml`（服务之间调用，DTO 在 `cn.refinex.meridian.api.internal.model`，`InternalOpenApiSpecTest` 校验，不做兼容性检查，ADR-0033）。
- 生成的 DTO：`target/generated-sources/openapi` 下的 `cn.refinex.meridian.api.model`，不进源码树；判别器联合是 sealed 接口（`SessionEvent`、`StreamOnlyEvent`、`UserEventInput`、`ContentBlock`）。
- 共用的生成选项在根 POM 的 `openapi-generator-maven-plugin` 的 `pluginManagement`；本模块的 execution 只生成模型，runtime 的 execution 只生成接口（`cn.refinex.meridian.runtime.openapi`）。

## 改规范的流程

1. 先改架构设计（新资源、新事件类型、破坏性变更走 ADR），再改规范；`OpenApiSpecTest` 中的端点与事件类型清单同步修改。
2. 字段说明、错误码、示例用中文；字段名 snake_case；错误响应引用 `components/responses` 中的 problem+json 响应。
3. 跑本模块测试（生成、编译、校验、兼容性），再跑 runtime 的编译与契约测试。
4. 破坏性变更只出现在新的大版本（新文件 `meridian-v2.yaml`），`OpenApiCompatibilityTest` 会拒绝在 v1 上做破坏性修改。

## 写作约束（3.1 与工具链的限制，ADR-0029）

- 带判别器的 `oneOf`：每个分支平铺写出全部属性，不用 `allOf` 继承；分支的判别属性写 `const`，值与 `discriminator.mapping` 的键相同（`OpenApiSpecTest` 检查）。否则契约测试的校验库误判，生成器多出 `*AllOfData` 类。
- 不把判别器联合嵌进另一个 `oneOf`：生成的接口 `getType()` 签名冲突，无法编译。需要"二者之一"时写 `type: object` 并在说明里指向两个 schema（例如 `SseFrame.data`）。
- 响应中总是出现的字段一律列入 `required`；可空写 `type: [T, 'null']` 并同样列入 `required`。删除非必填的响应字段，openapi-diff 报告为兼容。
- 不写 `nullable: true`（3.1 已移除，生成器忽略）；可空的对象引用写 `oneOf: [$ref, {type: 'null'}]`。
- SSE 响应写 `type: array`、`format: event-stream`、`items: $ref SseFrame`，runtime 生成时映射为 `ServerSentEvent<String>`。
- 3.1 的多类型（`type: [string, array]`）生成器不支持：写成命名的 `oneOf` schema，在本模块的生成配置 `schemaMappings` 中映射为 `Object`（`EmbeddingInput`、`EmbeddingVector`）。一个操作两种响应（JSON 或 SSE）时生成器只取第一种：runtime 的生成配置把响应类型映射为 `Object`（`ChatCompletion`），处理器按请求返回 DTO 或 `Flux<ServerSentEvent<String>>`（ADR-0033）。
- openapi-diff 检测不到 3.1 的字段类型变化，`OpenApiCompatibilityTest` 的补充检查只比较已有字段与参数的类型和格式；不使用 `if/then/else`、`dependentSchemas` 等它们都不理解的关键字。

## 易错点

- 生成的 DTO 中 `const` 判别属性是单值枚举 `TypeEnum`，联合接口的 `getType()` 返回 `Enum`；运行时按类型分派用 `switch` 模式匹配 sealed 接口，不比较 `getType()`。
- 事件的 `data` 字段与 `rt.session_events.data` 的存储格式相同（ADR-0027）：改事件的字段要同时改 engine-spi 的事件记录与这里的 `*Data` schema，runtime 的契约测试按规范校验每种事件的样本。
- `@JsonProperty` 写死了 snake_case 字段名，DTO 不依赖 ObjectMapper 的命名策略。

## 对外接口

被 runtime（以及 T14a 起的 control-plane、T18 起的 knowledge）依赖；T26 的 Java SDK 从同一份规范另行生成，不依赖本模块。
