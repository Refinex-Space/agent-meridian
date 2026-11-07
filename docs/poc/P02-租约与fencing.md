# P2 租约与 fencing：`FencedAgentStateStore` 在各写入路径下的租约校验

| 项 | 内容 |
|---|---|
| 状态 | 通过：存储层（T05）与中断、出错、停机三条完整路径（T06） |
| 通过标准 | 两个 Worker 争抢同一线程，失去租约的一方在中断、出错、停机各路径下都写不进任何状态（架构设计「M0 技术验证」） |
| 环境 | macOS arm64，Temurin 25.0.5+8，Testcontainers 中的 PostgreSQL 17（`deploy/docker/postgres` 构建，pgvector + zhparser） |
| 版本 | AgentScope 2.0.4、Spring Boot 4.1.2；T05、T06 验证于 2026-11-07 |

## 范围

P2 分两部分验证：

- **存储层（T05，本报告）**：失去租约的节点直接调用每一个写入方法，确认数据库拒绝且没有任何变化。这一层与 AgentScope 何时写状态无关，只要它的写入都经过 `FencedAgentStateStore`，就逃不出校验。
- **完整路径（T06，见下文"完整路径"）**：用 `ScriptedModel` 驱动真实的一轮，在中断、出错、优雅停机三条路径上让旧 Worker 失去租约，确认 AgentScope 在这些路径上触发的状态保存、事件与账本写入全部被拒绝。

## 机制

- 状态写入：`FencedAgentStateStore`（engine-agentscope）按线程 ID 从 `LeaseRegistry` 取租约（子线程取父线程的），交给 runtime 的 `JdbcAgentStateRepository`。后者在一个事务里先执行
  `SELECT tenant_id, project_id FROM rt.turns WHERE thread_id = ? AND status = 'running' AND lease_owner = 本节点 AND lease_epoch = ? FOR SHARE`，
  取不到行就抛 `LeaseLostException`，什么都不写；取到行才写 `rt.agent_states`，租户与项目取自这一行。
- 事件写入：`SessionEventWriter` 对带租约条件的批次以同样方式按轮次 ID 锁住轮次行，不满足时返回 `LeaseLost`，不占用 seq；`EventSinkPort` 的实现把它转成 `LeaseLostException`。
- 为什么用 `FOR SHARE` 而不是写入语句里的 `EXISTS` 子查询：READ COMMITTED 下，写入语句在会话行上等锁后重新检查条件时，子查询沿用语句开始时的快照，可能在回收器已提交之后照常写入。`FOR SHARE` 与回收器、置回队列的 `UPDATE` 冲突，写入事务提交前租约不会被收回；回收器用 `SKIP LOCKED` 跳过正在写入的轮次（ADR-0027 第 3 条）。
- 同节点重新认领：本节点上一次执行结束（`LeaseHandle.release()`）之前，认领 SQL 排除该线程，`LeaseRegistry` 也用 `putIfAbsent` 预留，所以旧执行按线程 ID 查到的 epoch 永远是它自己的，残余写入不会借用新 epoch（ADR-0027 第 2 条）。

## 做法

集成测试 `meridian-runtime` 的 `config.LeaseFencingIT`，状态写入走生产的 `FencedAgentStateStore` 加 runtime 的端口实现，两个节点（`node-a`、`node-b`）共享数据库与可控时钟：

1. `node-a` 认领轮次（epoch 1），在持有租约时写入两个状态键与一个事件。
2. 时钟推进 31 秒，`node-b` 的回收器把轮次置回队列，`node-b` 认领（epoch 2）。`node-a` 的执行没有结束，它的 `LeaseRegistry` 仍登记 epoch 1，所以下面的拒绝只能来自数据库。
3. 记录快照：该线程 `rt.agent_states` 的每一行（`state`、`version`、`content_epoch`、`updated_at`），会话的 `last_seq` 与事件数。
4. `node-a` 依次调用各写入方法，再比较快照。
5. `node-b` 照常写入，`content_epoch` 变为 2。

命令：

```bash
./mvnw -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=LeaseFencingIT -Dfailsafe.failIfNoSpecifiedTests=false
```

## 结果

失去租约的 `node-a` 的写入：

| 写入 | 结果 |
|---|---|
| `save(userId, threadId, key, State)` | `LeaseLostException` |
| `saveIfVersion(..., expectedVersion = 1)`（版本匹配） | `LeaseLostException` |
| `saveIfVersion(..., UNVERSIONED)`（无条件写） | `LeaseLostException` |
| `saveIfVersion(..., expectedVersion = 0)`（不存在才写，新键） | `LeaseLostException` |
| `save(userId, threadId, key, List<State>)` | `LeaseLostException` |
| `delete(userId, threadId, key)` | `LeaseLostException` |
| `delete(userId, threadId)`（整个线程） | `LeaseLostException` |
| `SessionEventWriter.append`（带 epoch 1 的租约条件） | `AppendResult.LeaseLost`，不占用 seq |
| `EventSinkPort.append`（Reactor Context 中 epoch 1） | `LeaseLostException` |

快照前后完全相同：状态行、版本号、`content_epoch`、`updated_at`、`last_seq`、事件数都没有变化。`node-b` 随后的写入成功，`content_epoch` 为 2。

同一测试类中的其他断言：

- 租约条件同时校验节点：`node-a` 带上正确的 epoch 2，仍因 `lease_owner = node-b` 被拒绝。
- 本节点没有租约条目时，`FencedAgentStateStore` 在访问数据库之前就拒绝。
- 子线程登记到父线程后，写入以父轮次的租约为条件；父轮次被接管后子线程的写入同样被拒绝。
- 权限上下文的安装写入（`markPermissionInstall`）不更新 `content_epoch`，其后的内容写入更新为当前 epoch。
- 持有者的 `saveIfVersion` 与 AgentScope 2.0.4 语义一致：期望 0 但已存在、版本不匹配都返回 `UNVERSIONED` 且不写。

相关的调度测试（同一模块）：

- `TurnClaimIT.sameNodeDoesNotReclaimAThreadUntilItsPreviousExecutionEnds`：租约被回收后，旧执行结束前本节点不会再认领该线程，`LeaseRegistry` 中仍是旧 epoch；释放后再认领得到 epoch 2。
- `LeaseLifecycleIT.staleExecutionCannotRequeueOrFinishAndNothingChanges`：失去租约的执行置回队列、结束轮次都返回 `LeaseLost`，轮次、计数与事件不变。
- `SessionEventWriterIT.leaseLostWriteWritesNothingAndConsumesNoSeq`：epoch 不符或节点不符的事件写入不占用 seq，后续写入不跳号。

## 完整路径（T06）

集成测试 `meridian-runtime` 的 `config.WorkerLeaseFencingIT`。两个节点各有完整的 Worker 与真实装配的 `AgentScopeEngine`，状态写入经 `FencedAgentStateStore` 加 `JdbcAgentStateRepository`，`node-a` 的状态端口外包一层记录被拒绝的写入：

1. `node-a` 认领（epoch 1），在内核层开始执行一轮：模型发起一次工具调用，记账的测试工具在账本中记下开始后卡住。
2. 时钟推进 31 秒，`node-b` 回收并认领（epoch 2）。`node-a` 还没察觉：它的 `LeaseRegistry` 仍是 epoch 1，租约守卫放行，AgentScope 照常走到保存状态的路径，拒绝只能来自数据库。
3. 记录快照：该线程的智能体状态行（含版本、`content_epoch`、`content_turn_id`、`updated_at`）、会话状态与 `last_seq`、事件数、轮次行、工具账本、收件箱、待处理动作。
4. 放行工具，让旧执行走到要测的路径；再用旧执行的 epoch 写它产生的每个持久化事件、写一条账本记录，并按它的终态结束或置回轮次。

| 路径 | 触发 | AgentScope 的状态保存 | 旧执行的事件 | 旧执行的账本 | 结束或置回轮次 |
|---|---|---|---|---|---|
| 中断 | `AgentEngine.interrupt`（USER） | `handleInterrupt` 的保存被拒绝（AgentScope 记录告警后照常以 `INTERRUPTED` 结束） | 每个都返回 `LeaseLost`，不占用 seq | 工具的结束记录与新的开始记录都抛 `LeaseLostException` | `TurnFinisher.finish` 返回 `Abandoned` |
| 出错 | 工具执行后模型抛出错误（关闭批次 checkpoint） | `saveStateAfterCallFailure` 的保存被拒绝（作为被抑制的异常附在原错误上），本轮以 `session.error` 加 `status_idle(error)` 结束 | 同上 | 同上 | — |
| 停机 | `AgentEngine.shutdown(60s)`（SYSTEM） | `ShutdownStateSaver` 的保存被拒绝（AgentScope 记录告警），本轮以 `status_rescheduled(node_shutdown)` 结束 | 同上 | 同上 | `TurnFinisher.requeue` 返回 `Abandoned` |

三条路径上，记录到的被拒绝写入都大于 0；快照前后完全相同，账本中的调用仍是开始时的 `running`。另一个用例让旧 Worker 完整运行：`node-b` 接管后 `node-a` 续租发现租约丢失（`REVOKED`），Worker 只取消订阅、不调用 `interrupt`，以 `Abandoned` 结束并释放本节点的条目，指标 `meridian_lease_lost_total` 加一，数据库快照不变。

命令：

```bash
./mvnw -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=WorkerLeaseFencingIT -Dfailsafe.failIfNoSpecifiedTests=false
```

## 结论

通过。存储层：失去租约的节点无法经任何写入方法改变智能体状态与事件，数据库保持不变（T05）。完整路径：AgentScope 在中断、出错、停机三条路径上触发的状态保存，旧执行的事件与工具账本写入，以及结束、置回轮次，全部被数据库拒绝（T06）。
