# T05 运行时数据层与调度租约

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T04 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P2 |

启动方式：在仓库根目录开新会话，输入 `/task T05`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

架构原则第 4 条：所有写入受租约约束。本任务建立 runtime 的数据层和调度：`rt` schema 的完整表结构、轮次认领、续租、回收、租户并发计数，以及 `AgentStateRepository`、`LeaseRegistry` 等端口的数据库实现。

调度出错的后果是同一轮被执行两次（副作用重复）或永远不被执行（会话卡死），而这类问题很难在线上复现。所以本任务的重点是并发正确性，并用可重复的测试证明它。

## 必读

1. 架构设计「数据架构」（核心表中的 runtime 部分、跨服务数据流、数据保留）、「运行时架构」中的「一轮执行的生命周期」「租约与 fencing」「状态机」。
2. `docs/engineering/coding-standards.md`「数据访问」「并发与响应式」。
3. 已有模块的 `CLAUDE.md`：`meridian-persistence`、`meridian-engine-spi`、`meridian-engine-agentscope`。

## 范围

### 要做

- `meridian-runtime` 的应用骨架：Spring Boot WebFlux 主类、配置、profile（`dev`、`test`）、健康检查。
- `rt` schema 的 Flyway 迁移：架构设计 runtime 核心表全部建出，包括后续任务才使用的表，避免频繁改表。
- 仓储：本任务与 T06 需要的 `sessions`、`threads`、`turns`、`turn_inputs`、`tenant_concurrency`、`session_events`、`agent_states`、`outbox`。
- `dispatch` 上下文：认领、续租、回收、置回队列、同节点重新认领规则。
- 端口实现：`LeaseRegistry`、`AgentStateRepository`、`EventSinkPort` 的事件写入部分。
- M0 验证项 P2 的存储层部分与报告。

### 不做

- 轮次 Worker 与执行（T06）、HTTP API（T07）。
- 其他表的仓储（使用它们的任务负责）。

## 交付物

### 1. 迁移

- 架构设计 runtime 核心表全部建出：列、类型、主键、外键（同 schema 内）、索引、`COMMENT ON` 中文注释。
- `turns` 的部分唯一索引：`UNIQUE (thread_id) WHERE status IN ('queued','running','awaiting_action')`。
- `session_events` 按 `session_id` 哈希分 16 个分区，主键 `(session_id, seq)`。
- `agent_states` 包含 `user_id`、`thread_id`、`state_key`、`state jsonb`、`version`、`content_epoch`，以及租户与项目列。
- 应用以 `meridian_app` 角色运行，迁移以 `meridian_migrator` 执行。

### 2. 调度

- **认领**：严格按架构设计中的三步 SQL，在一个事务里完成。拿不到租户名额时回滚整个事务。
- **唤醒**：轮次入队时发布 `dispatch.wakeup`；Worker 在通知与 1 秒轮询之间取先到者。
- **租户并发计数行**：为某个租户第一次插入轮次时，同一事务中执行 `INSERT INTO rt.tenant_concurrency ... ON CONFLICT DO NOTHING`，`max_running` 取配置的全局默认值；T14a 起改为取租户配额（架构设计「认领」中的租户并发计数行）。
- **续租**：每 10 秒一次，带 epoch 条件更新 `lease_expires_at`；同时读取 `interrupt_requested_at` 并回调给执行方。续租连续失败超过 15 秒，通知执行方取消。
- **回收器**：每个节点每 5 秒运行一次，`SKIP LOCKED`；把租约过期的 `running` 轮次置回 `queued`，`recovery_attempt` 加一，租户并发计数减一，发出 `session.status_rescheduled` 事件，全部在同一事务里。
- **置回队列的三种情况**：按架构设计的表实现，区分 `recovery_attempt` 是否加一。
- **同节点重新认领**：本节点上一个线程的执行在结束前，不允许本节点再次认领同一线程；`LeaseRegistry` 在执行结束时才移除条目。
- 所有时间相关逻辑使用注入的 `Clock`；数值来自配置属性，默认值与架构设计一致，注释注明章节。

### 3. 端口实现

- `LeaseRegistry`：本节点持有的线程租约（线程 ID 到 epoch），支持把子线程登记到父轮次的租约下，支持"权限安装写入"标记。
- `AgentStateRepository`：读写 `agent_states`。每次写入在同一事务中校验 `turns` 表上的租约条件（`status='running' AND lease_owner = 本节点 AND lease_epoch = 期望值`），不满足时不写并报告租约丢失。实现 `saveIfVersion` 的条件更新与 `content_epoch` 规则。
- 事件写入：在一个事务里分配 `seq`（`UPDATE sessions SET last_seq = last_seq + 1 RETURNING last_seq`）并插入 `session_events`，带 epoch 条件；Worker 写入与 API 写入使用同一个入口。
- 调度、`AgentStateRepository`、事件写入的实现继承 `SystemRepository`，放在各上下文的 `infra.system` 包并标注 `@CrossTenant`（架构设计「租户隔离」）；会话、线程等按会话访问的仓储继承 `TenantScopedRepository`。

### 4. 阻塞调用

runtime 中所有 JDBC 调用都经 `Blocking` 调度。测试中开启 BlockHound。

### 5. P2 报告

`docs/poc/P02-租约与fencing.md`：本任务覆盖存储层（直接调用各写入方法）；中断、出错、停机三条完整路径由 T06 补充后定稿。

## 实现要点

- 并发测试使用真实 PostgreSQL（Testcontainers），用多个线程或多个 Worker 实例制造竞争；用可控时钟推进时间，不用 `sleep` 等待租约过期。
- 每条带条件的更新都检查影响行数，并以明确的结果类型返回给调用方，不吞掉"0 行更新"。
- 认领 SQL 的执行计划用 `EXPLAIN` 确认走索引，结果写进模块 `CLAUDE.md`。

## 验收标准

- [T05-A01] Flyway 迁移在空库上执行成功；表、索引、分区、注释与架构设计一致。验证：迁移测试，加一份 `\d+` 输出摘要。
- [T05-A02] 同一线程并发插入两条 `queued` 轮次时只保留一条，不产生无法认领的"毒丸"。验证：集成测试。
- [T05-A03] 多个 Worker 并发认领，同一轮次只被一个 Worker 认领；租户名额满额时，该租户的轮次不被认领，其他租户不受影响；新租户不需要手工插入计数行就能被认领。验证：并发集成测试。
- [T05-A04] 租约过期后，回收器在一个周期内把轮次置回 `queued`，`recovery_attempt` 加一，租户并发计数减一，产生 `session.status_rescheduled` 事件。验证：集成测试（可控时钟）。
- [T05-A05] P2 存储层：失去租约的节点调用 `save`、`saveIfVersion`（含无条件写）、`delete`、事件写入，全部被拒绝且数据库无变化。验证：集成测试。
- [T05-A06] 同节点重新认领规则：上一次执行结束前，本节点不会再认领同一线程。验证：测试。
- [T05-A07] 事件 `seq` 在同一会话内严格递增且连续，Worker 与 API 并发写入时也不重复、不跳号。验证：并发集成测试。
- [T05-A08] BlockHound 在 runtime 测试中开启，无违规。验证：测试报告。
- [T05-A09] `docs/poc/P02-租约与fencing.md` 已写（存储层部分）。验证：文件路径。
- [T05-A10] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 创建 `meridian-runtime` 的模块 `CLAUDE.md`：应用启动方式、profile、迁移位置、调度的不变量、并发测试的写法。
