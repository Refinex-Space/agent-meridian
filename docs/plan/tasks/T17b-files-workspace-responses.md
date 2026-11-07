# T17b 运行时补全：文件、工作区、用量事件与 Responses

| 项 | 内容 |
|---|---|
| 阶段 | 2 M1 控制面与治理 |
| 依赖 | T17a |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 中 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T17b`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

本任务补齐 runtime 公开 API 中剩下的部分，让业务方的接入完整可用：文件上传、工作区读写、`session.usage` 事件、OpenAI 兼容的 Responses 接口。

## 必读

1. 架构设计「API 设计」（资源表中文件、工作区、模型直通的行）、「工作区与沙箱」中的产物与受保护路径。
2. 已有模块 `CLAUDE.md`，以及 `meridian-v1.yaml`。

## 范围

### 要做

- 文件：`POST /v1/files`（上传到对象存储，按项目隔离，大小上限可配置）、`GET /v1/files/{id}`、`GET /v1/files/{id}/content`；`user.message` 内容可以引用文件，轮次开始时把引用的文件放进工作区。
- 工作区：`GET/PUT /v1/sessions/{id}/workspace/files/{path}`、`GET /v1/sessions/{id}/workspace:list`；`none` 档读写平台存储，沙箱档只读 `outputs/` 的副本；写受保护路径被拒绝。
- `session.usage` 事件：每轮结束时产生，带本轮与会话累计用量。
- `POST /v1/responses`：OpenAI Responses 兼容的一个子集（文本输入输出、流式、`usage`），子集范围在规范中写明，不支持的参数返回明确的错误。

### 不做

- 控制台页面（T24、T25a）。

## 实现要点

- 文件内容的 MIME 类型以服务端检测为准，不信任客户端声明；文件名做规范化，防止路径穿越。
- `/v1/responses` 与 `/v1/chat/completions` 共用 `ModelInvoker`，不另写一套限流与计量。

## 验收标准

- [T17b-A01] 文件上传后在 `user.message` 中引用，智能体能在工作区读到该文件。验证：场景测试。
- [T17b-A02] 工作区 API 在两个档位下的行为符合要求；写受保护路径被拒绝；路径穿越被拒绝。验证：集成测试。
- [T17b-A03] 每轮结束产生 `session.usage`，数值与账本一致。验证：集成测试。
- [T17b-A04] `/v1/responses` 子集的流式与非流式响应符合规范；不支持的参数返回明确错误。验证：契约测试。
- [T17b-A05] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-runtime`、`meridian-api` 的 `CLAUDE.md`。
