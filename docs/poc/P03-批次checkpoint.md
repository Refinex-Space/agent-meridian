# P3 批次级 checkpoint：每批工具执行完成后保存状态

| 项 | 内容 |
|---|---|
| 状态 | 通过（T06），已实现为默认开启的行为 |
| 通过标准 | 每批工具执行后调用 `saveAgentState` 得到批次级 checkpoint；强杀后从批次续跑，tool_use 与 tool_result 配对（架构设计「M0 技术验证」） |
| 环境 | macOS arm64，Temurin 25.0.5+8；Testcontainers 中的 PostgreSQL 17、Redis、MinIO |
| 版本 | AgentScope 2.0.4、Spring Boot 4.1.2；验证于 2026-11-07 |

## 结论

通过。在"一批工具执行完之后的下一次推理之前"调用 `ReActAgent.saveAgentState(RuntimeContext)`，保存的状态一致：强杀后另一节点从这个 checkpoint 续跑，上下文中 tool_use 与 tool_result 一一配对，已完成的工具（包括 `non_idempotent`）不再执行。实现为 `BatchCheckpointMiddleware`，默认开启，配置 `meridian.runtime.worker.batch-checkpoint: false` 可关闭（ADR-0028）。代价是每批工具多一次状态写入。

## 保存时机

保存不能放在 `onActing` 的事件流结束时。2.0.4 的 `ReActAgent.acting` 先跑完 `onActing` 中间件链，之后才在 `notifyPostActingHook` 中把工具结果写进 `AgentState`；那一刻保存得到的是只有 tool_use、没有 tool_result 的状态。下一次 `onReasoning` 开始时整批结果都已在上下文中，所以中间件在 `onActing` 完成时只做标记，在下一次推理前保存：

- `saveAgentState(ctx)` 保存的是本次调用正在使用的状态对象：`activateSlotForContext` 每次调用从存储重新加载状态后放进 `stateCache`，与 `CallExecution.state` 是同一个对象。
- 保存经 `FencedAgentStateStore`，受租约约束，是内容写入（更新 `content_epoch` 与 `content_turn_id`）；乐观并发的版本号由 AgentScope 的 `slotVersions` 维护，之后调用结束时的保存使用新版本，不产生冲突。
- 一批工具执行完就结束调用（直接返回、挂起、请求停止）时没有下一次推理，AgentScope 在调用结束时保存。
- 保存是阻塞的 JDBC，经 `Blocking` 在虚拟线程上执行。

## 做法与数据

集成测试 `meridian-runtime` 的 `config.BatchCheckpointIT`，两个节点、强杀与接管的做法同 [P10](P10-恢复语义.md)：

| 场景 | 强杀点 | 续跑方式 | 续跑时模型看到的上下文 | 工具执行次数 |
|---|---|---|---|---|
| 开启 checkpoint | 第一批（`lookup` 与 `non_idempotent` 的 `write_record` 并行）完成后、第二次模型调用开始时 | `RecoveryOnly`（本轮写过内容） | 用户消息 1 条；`call_a`、`call_b` 的 tool_use 与 tool_result 一一配对；恢复说明标为已成功 | 各 1 次 |
| 关闭 checkpoint（对照） | 同上 | 重放完整输入 | 用户消息 1 条；没有本轮的 tool_use；工具结果只出现在恢复说明的摘要里 | 各 1 次 |
| 开启 checkpoint | 第二批工具执行中途 | `RecoveryOnly`，从第一批的 checkpoint 续跑 | 只有第一批的 tool_use 与 tool_result（第二批的 tool_use 在 checkpoint 之后才写入上下文，没有悬空的调用）；恢复说明中第二批的调用为"状态未知" | 第一批的 `non_idempotent` 工具 1 次 |

写入开销（同一个两批工具的轮次，`agent_state` 的最终版本号）：

| | 状态写入次数 |
|---|---|
| 关闭 checkpoint | 2（安装权限 1 次，调用结束 1 次） |
| 开启 checkpoint | 4（另加两批各 1 次） |

命令：

```bash
./mvnw -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=BatchCheckpointIT -Dfailsafe.failIfNoSpecifiedTests=false
```

内核层的单元验证 `meridian-engine-agentscope-test` 的 `turn.RecoveryMiddlewareScenarioTest`：第二次模型调用发生时，存储中已经有第一批两个调用的 tool_use 与结果，`content_epoch` 为本次执行的 epoch。

## 与其他机制的关系

- 恢复判定：checkpoint 是内容写入，强杀后本轮"写过内容"，恢复走 `RecoveryOnly`；最近的内容写入不是失败那次执行时（例如更早一次执行的 checkpoint），失败那次执行消费的 steer 仍会重新投递（ADR-0028 第 1 条）。
- steer：`SteerMiddleware`（T08）必须排在 `BatchCheckpointMiddleware` 之外，一次推理前注入的 steer 才在该次 checkpoint 中；否则强杀后从 checkpoint 续跑会丢失它。
- 恢复说明：`RecoveryNoteMiddleware`（90）排在 `BatchCheckpointMiddleware`（80）之外，恢复轮注入的说明进入之后的 checkpoint。
