# M1 后端验收预演

以业务系统与管理员的身份，经 HTTP 跑通架构设计「M1 MVP」八个验收场景的后端部分（R2-A05）。T29 的 `e2e/m1/` 用 Java SDK 与 Playwright 重写同样的场景；这里只依赖 Python 3 标准库，方便在控制台完成之前随时回归。

| 场景 | 内容 | 依赖 |
|---|---|---|
| 1 | 知识库问答：带权限标签的文档只对有对应标签的终端用户可见（REST 检索与智能体的 `knowledge_search`） | knowledge |
| 2 | 文档编制：智能体在会话沙箱里用内置 docx 技能生成 Word，从 `outputs/` 下载 | kind 集群 `meridian` 与已部署的沙箱 |
| 3 | 审批：智能体调用标记为 ask 的 MCP 工具，Webhook 收到 `requires_action`（验签），批准后继续 | 本机的 MCP 替身与 Webhook 接收方 |
| 4 | 自定义工具：业务方声明 `query_order`，回传结果后会话继续 | — |
| 5 | steer 在当前轮注入；interrupt 让当前轮在下一个检查点停止 | — |
| 6 | 强杀执行中的 runtime 节点，45 秒内由另一个节点续跑；SSE 续传的序号连续、不重复 | 两个 runtime 节点 |
| 7 | 会话预算耗尽以 `budget_exceeded` 停止，账本与 Redis 的四级计数一致 | Redis（直接读计数键） |
| 8 | 两个租户并发：一个打满并发上限，另一个的排队时间不受影响 | — |

## 运行

```bash
e2e/backend/env.sh up                 # 基础设施、Keycloak、脚本化模型服务、control-plane、runtime a（18080）与 b（18086）、knowledge
python3 e2e/backend/m1_backend.py     # 八个场景，默认用脚本化模型服务
python3 e2e/backend/m1_backend.py --live   # 场景 1、2、3 用 DashScope 的真实模型（需要 DASHSCOPE_API_KEY，约 15 次调用）
e2e/backend/env.sh down               # 停止服务进程；基础设施与 Keycloak 用 scripts/dev/down.sh
```

- `--only=1,3`、`--skip=2` 选择场景；没有 kind 集群时 `E2E_SANDBOX=false e2e/backend/env.sh up` 并 `--skip=2`。
- 脚本化模型服务加载 `model-scripts/` 中的场景脚本（`#script:e2e-*`）。端口 18090 上已有不带这些脚本的服务时，先停掉它。
- 每个场景新建自己的租户（所有者是开发 realm 的 `dev-admin`），只打印 ID、状态与 PASS/FAIL，不打印 API Key、令牌与凭据。
- 场景 6 通过 `/actuator/metrics` 找出认领本轮的节点，用 `env.sh kill-runtime` 强杀（`kill -9`），结束时用 `env.sh start-runtime` 重新启动它。
- 服务日志在 `e2e/backend/.run/`。
