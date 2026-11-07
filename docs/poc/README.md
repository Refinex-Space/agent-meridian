# M0 技术验证

M0 要用代码验证架构设计中依赖运行时行为、读源码无法确认的假设。验证项和通过标准以[架构设计的"M0 技术验证"](../architecture/architecture.md#m0-技术验证)一节为准，这里只跟踪进度。

每项完成后，在本目录写一份报告 `PNN-短标题.md`：验证环境与版本、做了什么、实测数据、结论（通过 / 不通过）。不通过的项必须提 ADR 调整方案。

| 编号 | 验证内容 | 状态 | 报告 |
|---|---|---|---|
| P1 | AgentScope 2.0.4 在 Boot 4.1.2、JDK 25 下运行，Jackson 2 与 3 共存 | 通过（T01 依赖解析，T03 运行验证） | [P01-运行基线.md](P01-运行基线.md) |
| P2 | `FencedAgentStateStore` 在各写入路径下的租约校验 | 未开始 | |
| P3 | 批次级 checkpoint | 未开始 | |
| P4 | 两个档位的文件系统装配 | 未开始 | |
| P5 | agent-sandbox + gVisor + 预热池 | 未开始 | |
| P6 | 平台工具 `matchRule` 与审批流程 | 未开始 | |
| P7 | 单 Pod 容量 | 未开始 | |
| P8 | MCP SDK 0.17.2 Servlet 传输暴露 MCP Server | 未开始 | |
| P9 | 外部 `SandboxContext` 跨轮次复用与子线程共享 | 未开始 | |
| P10 | 四种终止方式下的恢复语义 | 未开始 | |
| P11 | `clearStateCache` 后的内存稳定性 | 未开始 | |
| P12 | `SpawnSubagentTool` 并行拉起与中断传递 | 未开始 | |
| P13 | 平台中间件与 harness 中间件的执行顺序 | 进行中（T04 测得链路：`GracefulShutdownMiddleware` 固定最外层 → `PlatformContextMiddleware`(300) → `PlatformTracingMiddleware`(200) → `LeaseGuardMiddleware`(100) → `ToolSpanMiddleware`(50) → harness 自带的 6 个中间件（均为 1，按注册顺序）；span 层级 `turn` → `invoke_agent` → `chat`/`execute_tool` 批次 → 每个调用的 `execute_tool`，见 `MiddlewareChainTest`；压缩守护的结论由 T09a 给出） | 待 T09a |
| P14 | 出网代理按源 Pod IP 鉴权 | 未开始 | |
| P15 | 无人审批执行的权限改写 | 未开始 | |
