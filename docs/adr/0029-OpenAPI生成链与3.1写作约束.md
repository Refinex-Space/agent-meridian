# ADR-0029 OpenAPI 生成链与 3.1 写作约束

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（补充） |
| 日期 | 2026-11-07 |
| 决策人 | T07 执行会话 |
| 影响的架构设计章节 | 「仓库布局」中 `meridian-api` 与各应用的代码生成、「API 设计」中的「约定」、「防漂移机制」中的契约测试与 openapi-diff（只补充落地方式，不改条款） |

## 背景

架构设计规定公开 API 以 OpenAPI 3.1 为唯一定义：`meridian-api` 只生成 DTO，runtime 生成 reactive 服务端接口，control-plane 与 knowledge 生成 servlet 接口；契约测试与 openapi-diff 防止实现与规范漂移。技术基线是 Spring Boot 4.1.2 与 Jackson 3。openapi-generator 对 3.1 的支持仍标为 beta，openapi-diff 与请求校验库对 3.1 的支持程度也不明确，所以 T07 先用一份最小规范验证整条生成链，再写正式规范。

最小规范覆盖：带判别器的 `oneOf`（请求体中的用户事件、响应中的事件信封、嵌套在数组里的内容块）、3.1 的可空写法 `type: [string, 'null']`、`additionalProperties`（字符串映射与任意对象）、`const`、`Idempotency-Key` 请求头、PATCH 与 `text/event-stream` 响应。验证工程放在会话临时目录，用 Spring Boot 4.1.2 的 BOM 编译三份生成结果。

## 验证结果

| 项 | 结果 |
|---|---|
| openapi-generator 7.26.0（`spring` 生成器，`useSpringBoot4`、`useJackson3`） | DTO、reactive 接口（`Mono<ResponseEntity<T>>`，请求体为 `Mono<T>`）、servlet 接口都生成并编译通过（`Compiling 25 source files ... BUILD SUCCESS`，dto 28 个类、reactive 2 个、servlet 2 个） |
| Jackson 3 往返 | DTO 用 `tools.jackson` 的 `JsonMapper` 序列化与反序列化正确：判别器选中正确的子类，可空字段输出 `null`，`type` 只输出一次 |
| SSE | 响应写成 `type: array`、`format: event-stream`、`items: $ref SseFrame`，并在 runtime 的生成中 `schemaMappings: SseFrame=ServerSentEvent<String>`，得到 `Mono<ResponseEntity<Flux<ServerSentEvent<String>>>>`；servlet 侧为 `ResponseEntity<List<SseFrame>>` |
| openapi-diff 2.1.7 | 能读取并比较 3.1 规范：删除端点、新增必填请求字段、响应字段由必填改为可选、请求枚举缩小报告为不兼容；响应新增可选字段、响应枚举扩大、判别器新增分支报告为兼容。**检测不到 3.1 的类型变化**：`type: string` 改为 `integer`、`[string, 'null']` 改为 `[integer, 'null']` 或 `string`、`anyOf` 分支类型变化，都报告为兼容或无差异；删除非必填的响应字段也报告为兼容 |
| openapi-request-validator-core 3.0.0（原 swagger-request-validator，改用 networknt 校验器） | 按 3.1（JSON Schema 2020-12）校验请求与响应：类型数组、`const`、枚举、必填、`additionalProperties` 都生效。两处限制：默认对每个对象 schema 注入 `additionalProperties: false`，`allOf` 组合的每个分支都会因此拒绝其他分支的字段；判别器子类型没有 `const` 时，它按结构相似度给分支注入枚举，会把多个子类型配成同一个取值 |

## 候选方案

**DTO 的生成器**

- A：`spring` 生成器只生成模型。与服务端接口共用同一套模板与选项，判别器与校验注解的写法一致；代价是 DTO 带上 Spring 的 `@Nullable`、`@DateTimeFormat`。
- B：`java` 客户端生成器生成模型。不依赖 Spring，但与服务端接口的类型映射是两套配置，判别器的实现方式不同，契约漂移的风险反而更大。

**兼容性检查的接入方式**

- A：openapi-diff 的 Maven 插件。基线必须是构建前就存在的文件，"首个版本没有基线时跳过"只能靠 profile 的文件存在条件，而基线要从 git 取出、构建时才生成，无法表达。
- B：在 `meridian-api` 的单元测试中调用 openapi-diff-core，基线从 git 取出，没有基线时 `Assumptions.abort` 说明原因；同一个测试补上 openapi-diff 检测不到的 3.1 类型变化。

**事件信封的写法**

- A：每种事件一个 `allOf: [信封, {data}]`。规范短，但契约测试的校验库在 `allOf` 上误判（见上表），生成器也会为每个内联的 `data` 生成 `*AllOfData` 类。
- B：每种事件平铺写出信封的 8 个字段。规范长约 800 行，但生成器、校验库、openapi-diff 都按最直接的方式处理。信封字段的说明用 `$ref` 指向共用的小 schema，避免重复。

## 决策

DTO 选 A，兼容性检查选 B，事件信封选 B。具体约定：

1. **版本**：openapi-generator 7.26.0（7.27.0 于 2026-11-02 发布，未满两周）；openapi-diff-core 2.1.7；openapi-request-validator-core 3.0.0。插件版本在根 POM，库版本在 `meridian-bom`。
2. **生成选项**（根 POM 的 `pluginManagement`）：`useSpringBoot4`、`useJackson3`、`openApiNullable=false`（不引入 jackson-databind-nullable，PATCH 只做"未给出即不变"）、`useBeanValidation`、`useSpringBuiltInValidation`（接口上不加 `@Validated`，参数校验由 Spring 内建的方法校验完成，失败为 400；否则 `MethodValidationPostProcessor` 会抛 `ConstraintViolationException`，被映射为 500）、`documentationProvider=none`、`annotationLibrary=none`、`hideGenerationTimestamp`。
3. **生成位置**：`meridian-api` 生成 `cn.refinex.meridian.api.model`，开启 `useSealed`（判别器联合是 sealed 接口，可以 `switch` 穷举）；runtime 生成 `cn.refinex.meridian.runtime.openapi` 的接口（`interfaceOnly`、`skipDefaultInterface`、`useTags`、`reactive`），模型引用 `meridian-api` 的类。生成的代码在 `target/generated-sources/openapi`，不进源码树，不受 Spotless、Checkstyle 与 package-info 约定约束。
4. **DTO 的依赖**：`meridian-api` 增加 `jakarta.validation-api`、`jakarta.annotation-api`、`spring-context`（生成代码用到的注解），都由 Boot 管理版本。runtime 增加 `spring-boot-starter-validation`，生成接口上的 `@Valid` 与约束注解才会生效。
5. **兼容性检查**：`OpenApiCompatibilityTest` 以"上一个已提交版本"为基线：工作区的规范与最近一次改动它的提交不同，基线是那次提交中的版本；相同，基线是那次提交的父提交中的版本。这样本地改规范时与 HEAD 比较，CI 在提交之后仍检查最近一次改动。git 不可用、浅克隆取不到父提交、规范第一次提交时跳过并说明。openapi-diff 判为不兼容，或补充检查发现已有字段、参数的类型或格式变化，测试失败并输出变更说明。CI 的检出改为 `fetch-depth: 0`。
6. **契约测试**：runtime 的集成测试用 openapi-request-validator-core 校验每个请求与响应，开启默认的严格模式（未声明的字段报错）。SSE 的每一帧按帧的事件类型校验 `data` 是否符合对应的事件信封 schema。

**写作约束**（记在 `meridian-api` 的 CLAUDE.md，`OpenApiSpecTest` 检查第一条）：

- 带判别器的 `oneOf`：每个分支平铺写出全部属性，不用 `allOf` 继承；分支的判别属性写 `const`，取值与 `discriminator.mapping` 的键相同。生成器因此为每个分支生成单值枚举 `TypeEnum`，联合接口上的 `getType()` 返回 `Enum`。
- 不把判别器联合再嵌进另一个 `oneOf`（生成的接口 `getType()` 签名冲突，无法编译）；需要"二者之一"的地方写成 `type: object` 并在说明里指向两个 schema，例如 `SseFrame.data`。
- 响应中总是出现的字段一律列入 `required`，可空的写 `type: [T, 'null']` 并同样列入 `required`；否则删除字段时 openapi-diff 报告为兼容。
- 不写 `nullable: true`（3.1 已移除，生成器忽略它）；可空对象引用写 `oneOf: [$ref, {type: 'null'}]`。
- 字段类型变化 openapi-diff 检测不到，由 `OpenApiCompatibilityTest` 的补充检查覆盖；新写的检查之外的 3.1 关键字（`if/then/else`、`dependentSchemas` 等）不使用。

## 影响

- 架构设计无需修改条款；「防漂移机制」中的"API 一致：契约测试与 openapi-diff"由本 ADR 的第 5、6 条落地。
- 新增依赖：根 POM 的 openapi-generator 插件；BOM 中的 openapi-diff-core、openapi-request-validator-core（都只在 test 作用域使用，Apache-2.0）；`meridian-api` 的三个注解依赖；runtime 的 `spring-boot-starter-validation`。
- 风险：openapi-generator 的 3.1 支持仍是 beta，升级生成器时先在 `meridian-api` 编译全部 DTO 并跑契约测试；openapi-diff 只覆盖部分 3.1 语义，补充检查只比较已有字段与参数的类型和格式，其余变化靠代码评审。

## 附录：端点与事件类型对照清单

架构设计「资源」表中本任务范围内的端点，与规范中的操作一一对应：

| 架构设计 | 规范中的操作 | 作用域 |
|---|---|---|
| `POST /v1/sessions` | `createSession` | `sessions:write` |
| `GET /v1/sessions` | `listSessions` | `sessions:read` |
| `GET /v1/sessions/{id}` | `getSession` | `sessions:read` |
| `PATCH /v1/sessions/{id}` | `updateSession` | `sessions:write` |
| `DELETE /v1/sessions/{id}` | `deleteSession` | `sessions:write` |
| `POST /v1/sessions/{id}:archive` | `archiveSession` | `sessions:write` |
| `POST /v1/sessions/{id}/events` | `sendEvents` | `sessions:write` |
| `GET /v1/sessions/{id}/events` | `listEvents` | `sessions:read` |
| `GET /v1/sessions/{id}/stream` | `streamSessionEvents` | `sessions:read` |
| `GET /v1/sessions/{id}/threads` | `listThreads` | `sessions:read` |
| `GET /v1/sessions/{id}/threads/{tid}/stream` | `streamThreadEvents` | `sessions:read` |
| `GET /v1/sessions/{id}/pending-actions` | `listPendingActions` | `sessions:read` |

架构设计「事件」的事件类型清单与规范中的 schema：

| 域 | 事件类型 | 信封 schema（`data` 的 schema） |
|---|---|---|
| user（请求体为 `UserEventInput` 的分支） | `user.message`、`user.interrupt`、`user.tool_confirmation`、`user.custom_tool_result`、`user.recovery_confirmation` | `UserMessageEvent`（`UserMessageData`）、`UserInterruptEvent`（`EmptyData`）、`UserToolConfirmationEvent`、`UserCustomToolResultEvent`、`UserRecoveryConfirmationEvent` |
| agent | `agent.message`、`agent.thinking`、`agent.tool_use`、`agent.tool_result`、`agent.mcp_tool_use`、`agent.mcp_tool_result`、`agent.custom_tool_use`、`agent.context_compacted` | `Agent*Event`（`Agent*Data`），结果类带 `ToolResultState`、`PermissionOutcome`、`BlobRef` |
| session | `session.status_running`、`session.status_idle`、`session.status_requires_action`、`session.status_rescheduled`、`session.status_terminated`、`session.error`、`session.usage`、`session.message_consumed`、`session.thread_created`、`session.thread_status_running`、`session.thread_status_idle` | `Session*Event`（`Session*Data`），`status_requires_action` 的动作为 `PendingActionSummary` |
| span | `span.model_request_start`、`span.model_request_end` | `SpanModelRequestStartEvent`、`SpanModelRequestEndEvent`（用量为 `TokenUsage`） |
| 只在流上 | `event_start`、`event_delta`、`ping` | `EventStartEvent`、`EventDeltaEvent`、`PingEvent`，联合为 `StreamOnlyEvent` |

持久化的 26 种事件组成 `SessionEvent`，`data` 的字段与 `session_events.data` 的存储格式相同（ADR-0027）；runtime 的契约测试为每种事件构造样本并按规范校验，事件类型新增时 `OpenApiSpecTest` 与该测试都会失败。
