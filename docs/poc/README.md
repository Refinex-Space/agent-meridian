# M0 技术验证

M0 要用代码验证架构设计中依赖运行时行为、读源码无法确认的假设。验证项和通过标准以[架构设计的"M0 技术验证"](../architecture/architecture.md#m0-技术验证)一节为准，这里只跟踪进度。

每项完成后，在本目录写一份报告 `PNN-短标题.md`：验证环境与版本、做了什么、实测数据、结论（通过 / 不通过）。不通过的项必须提 ADR 调整方案。

| 编号 | 验证内容 | 状态 | 报告 |
|---|---|---|---|
| P1 | AgentScope 2.0.4 在 Boot 4.1.2、JDK 25 下运行，Jackson 2 与 3 共存 | 通过（T01 依赖解析，T03 运行验证） | [P01-运行基线.md](P01-运行基线.md) |
| P2 | `FencedAgentStateStore` 在各写入路径下的租约校验 | 通过（T05 存储层：失去租约的节点调用各写入方法全部被拒且数据库无变化；T06 完整路径：旧执行在中断、出错、停机三条路径上触发的状态保存、事件与账本写入全部被拒，快照不变） | [P02-租约与fencing.md](P02-租约与fencing.md) |
| P3 | 批次级 checkpoint | 通过（T06：一批工具执行完、下一次推理前保存，强杀后从批次续跑，tool_use 与 tool_result 配对、已完成的工具不再执行；默认开启，每批多一次状态写入） | [P03-批次checkpoint.md](P03-批次checkpoint.md) |
| P4 | 两个档位的文件系统装配 | 未开始 | |
| P5 | agent-sandbox + gVisor + 预热池 | 未开始 | |
| P6 | 平台工具 `matchRule` 与审批流程 | 进行中（T08 审批流程部分通过：ask 产生 `tool_confirmation`，批准执行、拒绝后智能体收到拒绝结果并继续、超时视为拒绝，内容级规则经 `matchRule` 生效；文件工具规则待 T10a，`bash(git status:*)` 待 T12a） | [P06-规则与审批.md](P06-规则与审批.md) |
| P7 | 单 Pod 容量 | 未开始 | |
| P8 | MCP SDK 0.17.2 Servlet 传输暴露 MCP Server | 未开始 | |
| P9 | 外部 `SandboxContext` 跨轮次复用与子线程共享 | 未开始 | |
| P10 | 四种终止方式下的恢复语义 | 通过（T06：强杀重放完整输入、出错与停机从保存的状态续跑、用户中断以 interrupted 结束；不重复用户消息、不丢 steer、不重复执行 `non_idempotent` 工具；修正了跨轮 epoch 碰撞，ADR-0028；T08 用 `SteerMiddleware` 重跑强杀场景：被失败执行消费的 steer 重新投递并再次注入，开启 checkpoint 时随状态保存，均不丢失、不重复） | [P10-恢复语义.md](P10-恢复语义.md) |
| P11 | `clearStateCache` 后的内存稳定性 | 未开始 | |
| P12 | `SpawnSubagentTool` 并行拉起与中断传递 | 未开始 | |
| P13 | 平台中间件与 harness 中间件的执行顺序 | 进行中（T04 测得链路：`GracefulShutdownMiddleware` 固定最外层 → `PlatformContextMiddleware`(300) → `PlatformTracingMiddleware`(200) → `LeaseGuardMiddleware`(100) → `RecoveryNoteMiddleware`(90，T06) → `InterruptCheckpointMiddleware`(88，T08) → `SteerMiddleware`(85，T08) → `BatchCheckpointMiddleware`(80，T06) → `ToolSpanMiddleware`(50) → harness 自带的 6 个中间件（均为 1，按注册顺序）；span 层级 `turn` → `invoke_agent` → `chat`/`execute_tool` 批次 → 每个调用的 `execute_tool`，见 `MiddlewareChainTest`；压缩守护的结论由 T09a 给出） | 待 T09a |
| P14 | 出网代理按源 Pod IP 鉴权 | 未开始 | |
| P15 | 无人审批执行的权限改写 | 通过（T08：显式 ask 规则在 `DONT_ASK` 下仍返回 ask，合成时改写为 deny 后，`unattended` 会话与子线程得到拒绝结果并继续推理、不停在等待审批；`unattended` 不能进入计划模式；安全类 ask 由平台自动拒绝并运行恢复轮） | [P15-权限改写.md](P15-权限改写.md) |
