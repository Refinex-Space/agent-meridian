# M0 技术验证

M0 要用代码验证架构设计中依赖运行时行为、读源码无法确认的假设。验证项和通过标准以[架构设计的"M0 技术验证"](../architecture/architecture.md#m0-技术验证)一节为准，这里只跟踪进度。

每项完成后，在本目录写一份报告 `PNN-短标题.md`：验证环境与版本、做了什么、实测数据、结论（通过 / 不通过）。不通过的项必须提 ADR 调整方案。

| 编号 | 验证内容 | 状态 | 报告 |
|---|---|---|---|
| P1 | AgentScope 2.0.4 在 Boot 4.1.2、JDK 25 下运行，Jackson 2 与 3 共存 | 通过（T01 依赖解析，T03 运行验证） | [P01-运行基线.md](P01-运行基线.md) |
| P2 | `FencedAgentStateStore` 在各写入路径下的租约校验 | 通过（T05 存储层：失去租约的节点调用各写入方法全部被拒且数据库无变化；T06 完整路径：旧执行在中断、出错、停机三条路径上触发的状态保存、事件与账本写入全部被拒，快照不变） | [P02-租约与fencing.md](P02-租约与fencing.md) |
| P3 | 批次级 checkpoint | 通过（T06：一批工具执行完、下一次推理前保存，强杀后从批次续跑，tool_use 与 tool_result 配对、已完成的工具不再执行；默认开启，每批多一次状态写入） | [P03-批次checkpoint.md](P03-批次checkpoint.md) |
| P4 | 两个档位的文件系统装配 | 通过（T10a `none` 档：一轮读写文件后本机没有会话文件，小文件内联、大文件进对象存储，受保护路径只读；T12a `sandbox` 档：kind 上 `bash` 与文件工具在 gVisor 沙箱中读写、写受保护路径被拒绝、大结果卸载到平台存储、runtime 本机与平台工作区存储都没有会话文件） | [P04-文件系统装配.md](P04-文件系统装配.md) |
| P5 | agent-sandbox + gVisor + 预热池 | 通过（T11：kind 单节点、gVisor、agent-sandbox v1.0.10，预热池命中时连续认领 50 次 p95 223 毫秒、全部命中；预热池补充 p50 1.9 秒；工作区 100 MB 快照约 3 秒、恢复约 4 秒，恢复后文件一致） | [P05-沙箱与预热池.md](P05-沙箱与预热池.md) |
| P6 | 平台工具 `matchRule` 与审批流程 | 通过（T08 审批流程：ask 产生 `tool_confirmation`，批准执行、拒绝后智能体收到拒绝结果并继续、超时视为拒绝，内容级规则经 `matchRule` 生效；T10a 文件工具规则：路径 glob 放行匹配路径、其他按默认处理，受保护路径拒绝，未读先改拒绝；T12a bash 规则：`bash(git status:*)` 自动放行，其他命令与有一段不匹配的复合命令触发审批，拒绝规则任一段命中即拒绝） | [P06-规则与审批.md](P06-规则与审批.md) |
| P7 | 单 Pod 容量 | 未开始 | |
| P8 | MCP SDK 0.17.2 Servlet 传输暴露 MCP Server | 预验证通过（T10b：`HttpServletStreamableServerTransportProvider` 与 SSE 传输注册为 Boot 4.1 Spring MVC 中的 Servlet，平台的 MCP 客户端完成初始化、工具发现与调用，凭据经请求定制器注入；真实的 `knowledge_search` 与 MCP Inspector 待 T19） | [P08-MCP-Server.md](P08-MCP-Server.md) |
| P9 | 外部 `SandboxContext` 跨轮次复用与子线程共享 | 通过（T12a：kind 上两轮之间沙箱不停止，同一会话的另一个线程读到主线程写的文件，技能资源在沙箱中且被篡改后下一轮按摘要恢复；harness 对外部沙箱不停止不持久化，缺少外部沙箱时会自建沙箱，平台以拒绝的客户端防止） | [P09-会话沙箱.md](P09-会话沙箱.md) |
| P10 | 四种终止方式下的恢复语义 | 通过（T06：强杀重放完整输入、出错与停机从保存的状态续跑、用户中断以 interrupted 结束；不重复用户消息、不丢 steer、不重复执行 `non_idempotent` 工具；修正了跨轮 epoch 碰撞，ADR-0028；T08 用 `SteerMiddleware` 重跑强杀场景：被失败执行消费的 steer 重新投递并再次注入，开启 checkpoint 时随状态保存，均不丢失、不重复） | [P10-恢复语义.md](P10-恢复语义.md) |
| P11 | `clearStateCache` 后的内存稳定性 | 未开始 | |
| P12 | `SpawnSubagentTool` 并行拉起与中断传递 | 未开始 | |
| P13 | 平台中间件与 harness 中间件的执行顺序 | 通过（T04 测得链路；T09a：压缩守护外层 20、内层 0 夹住 order 为 1 的 `CompactionMiddleware`，预算耗尽时压缩被阻止、失败摘要被拦截且历史不被替换；对照实验确认 2.0.4 会把失败文本写进历史；真实模型触发了一次真实压缩） | [P13-中间件顺序.md](P13-中间件顺序.md) |
| P14 | 出网代理按源 Pod IP 鉴权 | 未开始 | |
| P15 | 无人审批执行的权限改写 | 通过（T08：显式 ask 规则在 `DONT_ASK` 下仍返回 ask，合成时改写为 deny 后，`unattended` 会话与子线程得到拒绝结果并继续推理、不停在等待审批；`unattended` 不能进入计划模式；安全类 ask 由平台自动拒绝并运行恢复轮） | [P15-权限改写.md](P15-权限改写.md) |
