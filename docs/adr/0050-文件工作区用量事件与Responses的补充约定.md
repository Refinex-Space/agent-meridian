# ADR-0050 文件、工作区 API、用量事件与 Responses 的补充约定

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（补充） |
| 日期 | 2026-11-12 |
| 决策人 | T17b |
| 影响的架构设计章节 | 数据架构（核心表）、工作区与沙箱（文件系统装配、会话沙箱）、API 设计（资源、事件）、计量与预算、模型调用（直通接口）、端口 |

## 背景

T17b 补齐 runtime 公开 API 中剩下的部分。架构设计列出了资源（`POST /v1/files`、`GET /v1/files/{id}`、`GET /v1/files/{id}/content`，`GET/PUT /v1/sessions/{id}/workspace/files/{path}`、`GET /v1/sessions/{id}/workspace:list`，`POST /v1/responses`）与事件类型 `session.usage`，以下几处没有写到：

- **文件存在哪、属于谁、怎样进入工作区。** 规范里 `file`、`image` 块可以按 `fil_…` 引用，内核只把它翻译成文本占位（登记事项 D07）；`sandbox` 档的工作区在沙箱里，平台存储里没有它。
- **引用的校验与媒体类型。** 客户端声明的媒体类型、文件名都不可信；文件名会成为工作区中的路径。
- **steer 消息中的文件。** steer 在轮次中途经收件箱注入，此时"轮次开始时放置"已经过去。
- **工作区 API 的两档。** `none` 档的文件在 `rt.workspace_entries`，值是 AgentScope `RemoteFilesystem` 的 `FileData` 格式；`sandbox` 档只有每轮同步到对象存储的 `outputs/` 副本（ADR-0037）。路径里有 `/`，受保护路径对智能体只读。
- **`session.usage` 的口径。** 规范里它是"一次模型调用的用量"，但从未产生；任务要求每轮结束时给出本轮与会话累计，并与账本一致。
- **Responses 的子集。** 架构只写了"OpenAI 兼容"；ADR-0033 的对话补全只支持文本与函数工具，登记事项 D26 要求与 Responses 一起评估缺少的参数。

## 候选方案

**文件放进工作区的时机**

- A：上传时就写进某个会话的工作区。文件属于项目、可以被多个会话引用，`sandbox` 档上传时未必有沙箱。
- B（采纳）：轮次开始时，把本轮输入（新消息与自定义工具结果）引用的文件放到 `uploads/{file_id}/{filename}`。`sandbox` 档在获取沙箱之后、调用内核之前写入，与技能资源的同步同一时机。

**内核怎样知道文件的位置**

- A：把路径写进事件中的内容块。改变事件 `data` 的格式，路径是放置的结果，不是用户的输入。
- B（采纳）：`TurnContext` 新增 `workspaceFiles`（文件 ID → 工作区路径），由 runtime 放置后填写；翻译器按它给出路径，没有时保留原来的文本占位。

**steer 消息中的文件**

- A：消费 steer 时放置文件。收件箱的消费在带租约的事务里，`sandbox` 档还要远程写入，失败后的重投语义复杂。
- B（采纳）：引用了平台文件的消息总按 `queue` 投递，与"跟在中断后面的消息"一样降级（`EventAdmission`）。

**`session.usage` 的数据来源**

- A：轮次结束时汇总 outbox 中的 `usage` 消息。outbox 在发布后会被清理，载荷是 JSON。
- B（采纳）：新表 `rt.turn_usage`（轮次 × 模型的累计），在写 outbox `usage` 的同一事务中累加，所以与账本逐条一致；轮次结束时读出本轮与会话的合计，在终态事件之前、同一事务写入。

## 决策

1. **文件资源。** `rt.files`（项目级）：`id`（`fil_`）、规范化文件名、服务端检测的媒体类型、大小、SHA-256、对象键 `rt/{tenant}/{project}/files/{file_id}`、上传者（`PlatformContext.actor()`）。上传是 `multipart/form-data` 的 `file` 字段，作用域 `files:write`（控制台用户按 `SESSION_WRITE` 换算，ADR-0044 预留的映射）；读用 `sessions:read`。大小上限 `meridian.runtime.files.max-size`（默认 32 MB）同时设为 `spring.webflux.multipart.max-disk-usage-per-part`，超过返回 413 `file.too_large`；空文件 400。文件不随会话删除；一期没有删除接口与保留期（登记事项）。
2. **媒体类型与文件名。** 媒体类型由 Apache Tika（`tika-core`，技术基线的文档解析组件）按内容检测，文件名只作提示，客户端的 `Content-Type` 与块中的 `media_type` 都不采用。文件名取最后一段（`/` 与 `\` 都算分隔符），NFC 规范化，去掉控制字符与 `<>:"|?*`，去掉首尾空白与点，按 UTF-8 截到 255 字节并保留扩展名，结果为空时为 `file`。下载响应带检测的类型、`Content-Disposition: attachment` 与 `X-Content-Type-Options: nosniff`。
3. **引用的校验与补全。** 发送事件时，`user.message` 与 `user.custom_tool_result` 中按 `file_id` 引用的文件必须属于当前项目，否则 400（`request.validation_failed`，字段指到块）；按 `file_id` 引用的图片，检测类型必须是 `image/*`。保存的事件中 `filename` 为规范化后的名字（未给出时取文件的名字），`media_type` 为检测的类型。引用文件的消息按 `queue` 投递。
4. **放置。** `TurnExecution` 在准备沙箱之后、调用内核之前，经 `file.app.TurnFileStager` 放置本轮输入引用的文件：`none` 档经 `WorkspaceStorePort.putIfVersion(…, 0)` 写入（已存在不覆盖，恢复重放时保留智能体的修改），值是 `FileData` 格式（engine-spi 的 `WorkspaceFiles` 编码，与 `RemoteFilesystem` 互通，由内核测试守住）；`sandbox` 档经新增的 `SandboxDriver.writeFiles`（tar 经 `/upload` 写入工作区根，覆盖）。对象存储或沙箱暂时不可用时与沙箱获取失败相同：`session.error(retryable)` 加 `status_rescheduled`。按文件 ID 引用的图片同样作为文件放置，不作为图片输入模型（登记事项）。
5. **工作区 API。** 路径参数整体 URL 编码（`/` 写成 `%2F`），按 `WorkspacePaths` 的规则校验：空、以 `/` 开头、含 `.`/`..`/空段或反斜杠返回 400 `workspace.invalid_path`。档位取会话的环境修订：
   - `none` 档读写智能体使用的同一命名空间与键（`/` 加相对路径）。`PUT` 覆盖写入，上限同文件；受保护路径（`AGENTS.md`、`skills/`、`subagents/`、`tools.json`、`MEMORY.md`）写入返回 403 `workspace.protected_path`；会话已归档或删除返回 409 `session.terminated`。列表按键的字节序、游标为上一页最后的路径；大小来自新列 `workspace_entries.content_size`（写入时由 `WorkspaceFiles` 计算，之前的行为空）。
   - `sandbox` 档只读 `outputs/` 的副本（`rt.sandbox_outputs` 与 `rt/outputs/…`），其他路径 404 `workspace.file_not_found`；`PUT` 返回 409 `workspace.read_only`。
   - 响应的 `Content-Type` 由 Tika 按内容与路径检测。
6. **`session.usage`。** 每轮结束（`idle`、`requires_action`，含恢复用尽、无人值守的自动拒绝）时在终态事件之前、同一事务写入：`usage` 为本轮合计，`session_usage` 为会话累计（含本轮），`models` 为本轮按模型的明细，`model_id` 为本轮用量最多的模型（没有调用时省略，原来必填，事件从未产生过，openapi-diff 判为兼容）。只计 `model_tokens`（推理与压缩，含子线程的调用），与账本中本轮的记录之和相同。置回队列不写，同一轮的下一次执行继续累计。`rt.turn_usage` 不建会话外键（结算与清理可能并发，不能让账本写入失败），清理会话时按 `session_id` 删除。
7. **`/v1/responses`。** 与对话补全共用 `ModelInvoker` 与非流式聚合：输入为文本或消息数组（`user`、`assistant`、`system`、`developer`，内容为文本或 `input_text`、`output_text` 块），`instructions` 作为第一条系统消息，参数 `model`、`max_output_tokens`、`temperature`、`stream`，`store` 只接受 `false`；输出一条 `message` 输出项（思考内容不输出），结束原因为 `length` 时 `status=incomplete`。常见的不支持参数在请求 schema 中列出，给出时返回 400 `model.unsupported_parameter` 并逐项列在 `errors`，其他未知字段 400 `request.invalid`。流式事件按 OpenAI 的顺序给出 `response.created` 至 `response.completed`（或 `incomplete`），`sequence_number` 从 0 递增；首包之前的错误与对话补全相同，之后以 `response.failed` 结束。ID 为 `resp_`、`msg_` 加 ULID。
8. **D26 的评估。** 对话补全维持 ADR-0033 的子集：`top_p`、`stop`、`response_format` 等要扩展 `ModelInvoker.ChatRequest` 与三个供应商适配器，目前没有业务方需要；Responses 同样不支持它们，返回明确的错误。

## 影响

- 架构设计：「核心表」加 `files`、`turn_usage` 与 `workspace_entries.content_size`；「文件系统装配」「会话沙箱」加上传文件的放置；「资源」与「事件」写明工作区两档、文件与 `session.usage` 的口径；「模型调用」的直通接口加 Responses；「端口」表中 `SandboxDriver` 加 `writeFiles`。
- 代码：runtime 迁移 `V0011`（`files`、`turn_usage`、`workspace_entries.content_size`），新上下文 `file`（上传、查询、放置）与 `workspace` 的 API；`TurnContext.workspaceFiles`、`SessionEvent.Usage` 的新字段、`WorkspaceFiles`；`KubernetesSandboxDriver.writeFiles`；`PublicApiScopes` 加 `files:write`；依赖 `tika-core`（`meridian-bom` 声明版本）。
- 验证：文件引用的场景测试（A01），工作区两档的集成测试（A02），`session.usage` 与 outbox 用量逐项比对（A03），Responses 的契约测试（A04）。
