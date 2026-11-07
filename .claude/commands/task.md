---
description: 按 docs/plan/tasks/ 中的任务提示词执行一个交付任务
argument-hint: <任务编号，如 T05 或 R1>
disable-model-invocation: true
---

要执行的任务编号：$ARGUMENTS

如果上面的任务编号为空，或者 `docs/plan/tasks/` 下不存在以该编号加短横线开头的文件，停止并请用户给出正确的编号，不要猜测。

否则：

1. 找到 `docs/plan/tasks/` 下文件名以该编号加短横线开头的任务文件，完整读取它。
2. 把这个文件的全部内容当作本次会话的任务提示词，严格按它执行。它要求先读的 `docs/plan/protocol.md` 等文件，同样要完整读取并遵守。
3. 完整执行任务。只在协议规定的情况下停下：前置条件不满足、变更型 ADR、设计关口、需要用户授权的操作。

当前仓库状态：

!`git status --short`

最近的提交：

!`git log --oneline -5`
