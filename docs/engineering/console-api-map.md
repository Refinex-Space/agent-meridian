# 控制台页面到接口的对照表

控制台（T21 至 T25b）只消费这里列出的接口。按架构设计「控制台」的十一个一级导航逐页列出展示的数据、可执行的操作、实时性要求与对应接口；"状态"一列为"已有"的接口在 T14a 至 T19 交付，"T19b"是本任务补齐的。新页面需要的数据不在表中时，先补接口再写页面，不在前端临时拼凑。

## 约定

- **两套规范。** 管理 API（`meridian-admin-v1.yaml`，control-plane，只接受 OIDC 令牌）与公开 API（`meridian-v1.yaml`，runtime、control-plane、knowledge 各自提供一部分）。控制台调公开 API 时带 OIDC access token 与 `Meridian-Project`，作用域按角色换算（ADR-0044）。
- **路径缩写。** `T:` 表示 `/admin/v1/tenants/{tenant_id}`，`P:` 表示 `/admin/v1/tenants/{tenant_id}/projects/{project_id}`；`/v1/…` 是公开 API，括号里是提供服务：R（runtime）、C（control-plane）、K（knowledge）。
- **实时性。**
  - SSE：事件流推送，断线按 `Last-Event-ID` 补齐。
  - 轮询 N 秒：页面可见时按间隔刷新（TanStack Query 的 `refetchInterval`），隐藏时停止。
  - 按需：打开页面与操作之后刷新。
- **权限。** 管理 API 每个操作所需的操作类别由处理器的 `@RequiresPermission` 声明，逐角色的结果是 control-plane 的角色矩阵测试表 `src/test/resources/rbac/role-matrix.psv`；公开 API 的作用域写在规范的 `security` 中。页面用 `GET /admin/v1/me/access` 取当前用户在所选项目上的操作类别与作用域，决定操作按钮显示或禁用（`PermissionGate`），不在前端复刻角色矩阵。
- **分页。** 列表一律游标分页 `?limit=&after=`，返回 `{data, has_more, next_cursor}`。

## 应用外壳

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 登录后的身份 | 用户标识、邮箱、是否平台管理员、所在租户与各租户中的角色 | 首次登录按邮箱绑定被邀请的成员（见「OIDC 下的成员」） | 按需 | `GET /admin/v1/me` | T19b |
| 项目切换 | 用户能进入的租户与项目 | 切换项目 | 按需 | `GET /admin/v1/me`（成员关系）、`GET T:/projects` | T19b、已有 |
| 权限守卫 | 当前用户在当前项目上的操作类别与公开 API 作用域 | 隐藏或禁用无权操作 | 切换项目时 | `GET /admin/v1/me/access?tenant_id=&project_id=` | T19b |
| 导航徽标 | 待处理动作数 | 跳到待处理页 | 轮询 30 秒 | `GET /v1/session-stats`（R）的 `pending_actions` | T19b |
| 顶部横幅 | 预算告警（软阈值、硬阈值） | 跳到用量与预算 | 轮询 5 分钟 | `GET P:/budget-status` 的 `state` | T19b |

## 1 概览

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 运行状况卡片 | 运行中的会话数、等待回应的会话数、待处理动作数 | 跳到会话列表（按状态筛选）、待处理页 | 轮询 30 秒 | `GET /v1/session-stats?window=24h`（R） | T19b |
| 按智能体的失败率 | 时间窗（1 小时、24 小时、7 天）内每个智能体的轮次数、失败轮次数、失败率 | 切换时间窗，点智能体跳到会话列表 | 轮询 60 秒（数据本身按分钟汇总） | `GET /v1/session-stats?window=`（R）的 `agents` | T19b |
| 最近错误 | 时间窗内最近 10 个失败轮次：会话、智能体、错误码、错误信息、时间 | 跳到会话详情的该轮 | 轮询 30 秒 | `GET /v1/session-stats`（R）的 `recent_errors` | T19b |
| 用量趋势 | 本项目按天的 token 与成本 | 切换时间范围 | 按需 | `GET P:/usage?group_by=day`（`USAGE_READ`） | 已有 |
| 本周期预算消耗 | 本月已用 token、项目级软硬阈值与来源（项目设置、默认值、未设置）、状态 | 跳到用量与预算 | 轮询 5 分钟 | `GET P:/budget-status`（`USAGE_READ`） | T19b |

## 2 会话

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 会话列表 | 会话 ID、智能体、状态、终端用户、创建与更新时间 | 按智能体、状态、终端用户、创建时间范围筛选；搜索框输入会话 ID 时直接打开，否则按终端用户筛选 | 轮询 15 秒，提示"有 N 条更新"后刷新 | `GET /v1/sessions?agent_id=&status=&end_user_id=&created_after=&created_before=`（R）；会话带 `agent_id` | T19b（筛选与 `agent_id`），已有（列表） |
| 会话头部 | 状态、绑定的智能体版本与环境修订、值守方式、元数据、用量 | 中断、归档、删除（确认对话框写明后果）、修改元数据 | SSE | `GET/PATCH/DELETE /v1/sessions/{id}`、`POST /v1/sessions/{id}:archive`、`POST /v1/sessions/{id}/events`（`user.interrupt`）（R） | 已有 |
| 事件时间线 | 事件、子线程分叉、工具入参与结果、权限结论、上下文构成、token 与成本 | 选中事件看原始 JSON；按线程过滤 | SSE | `GET /v1/sessions/{id}/events`、`GET /v1/sessions/{id}/stream?threads=`（R） | 已有 |
| 线程树 | 主线程与子线程、状态 | 切换线程 | SSE | `GET /v1/sessions/{id}/threads`、`GET /v1/sessions/{id}/threads/{tid}/stream`（R） | 已有 |
| 待处理动作 | 本会话等待回应的动作与建议规则 | 批准、拒绝（可选记住此决定）、回传自定义工具结果、恢复确认 | SSE | `GET /v1/sessions/{id}/pending-actions`、`POST /v1/sessions/{id}/events`（R） | 已有；建议规则 T19b |
| 工作区 | 工作区文件树与内容 | 下载；`none` 档可编辑 | 按需（会话 idle 时刷新） | `GET /v1/sessions/{id}/workspace:list`、`GET/PUT …/workspace/files/{path}`（R） | 已有 |
| 回放 | 历史事件按时间重放 | 播放、暂停、拖动 | 按需 | `GET /v1/sessions/{id}/events`（R，分页读完后在前端回放） | 已有 |

## 3 Playground

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 选择智能体与环境 | 本项目已发布的智能体与当前版本、环境 | 选择后创建会话 | 按需 | `GET /v1/agents`（C）、`GET P:/environments` | 已有 |
| 对话 | 事件流、工具调用、待处理动作 | 发送消息（queue、steer）、上传文件、中断、审批 | SSE | `POST /v1/sessions`、`POST /v1/files`、`POST /v1/sessions/{id}/events`、`GET /v1/sessions/{id}/stream`（R） | 已有 |
| 工作区面板 | 工作区文件 | 查看、下载 | 每轮结束时刷新 | `GET /v1/sessions/{id}/workspace:list`、`GET …/workspace/files/{path}`（R） | 已有 |

## 4 智能体

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 智能体列表 | 名称、当前版本、更新时间 | 新建、删除（确认对话框写"该智能体的 N 个会话将无法继续"） | 按需 | `GET/POST P:/agents`、`DELETE P:/agents/{id}`；N 取 `GET /v1/session-stats?agent_id=`（R）的 `agents[].active_sessions` | 已有、T19b |
| 定义编辑 | 草稿规格（表单按 `agent-spec.schema.json` 校验） | 保存草稿、发布 | 按需 | `POST/GET/PUT/DELETE P:/agents/{id}/versions[/{v}]`、`POST …/versions/{v}:publish` | 已有 |
| 版本与对比 | 版本列表、两版本差异 | 选择两个版本对比 | 按需 | `GET P:/agents/{id}/versions`、`GET …/versions:compare` | 已有 |
| 子智能体拓扑 | 主智能体与子智能体的关系 | 点节点跳到对应定义 | 按需 | `GET P:/agents/{id}/versions/{v}` 的规格 `subagents` | 已有 |
| 运行情况 | 使用中的会话数、运行中的会话数、时间窗内的失败率 | 跳到会话列表（按该智能体筛选） | 轮询 60 秒 | `GET /v1/session-stats?agent_id=`（R） | T19b |

## 5 环境

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 环境列表 | 名称、档位、规格、当前修订 | 新建、删除 | 按需 | `GET/POST P:/environments`、`DELETE P:/environments/{id}` | 已有 |
| 编辑 | 档位、沙箱规格（资源上限）、出网白名单 | 保存（产生新修订） | 按需 | `GET/PUT P:/environments/{id}` | 已有 |
| 修订历史 | 每个修订的档位、规格、白名单、修改人、时间 | 查看某个修订 | 按需 | `GET P:/environments/{id}/revisions`、`GET …/revisions/{revision}` | 已有 |

## 6 能力

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 技能 | 技能、版本、frontmatter | 上传、发布新版本、删除 | 按需 | `GET/POST/DELETE P:/skills[/{id}]`、`GET/POST P:/skills/{id}/versions` | 已有 |
| MCP 服务 | 端点、工具清单与副作用、连接状态（最近一次探测的结果、时间、错误） | 登记、修改、删除、重新探测 | 按需 | `GET/POST/PUT/DELETE P:/mcp-servers[/{id}]`、`POST P:/mcp-servers/{id}:probe`；连接状态是 `last_check`（`ok` 或 `error`，带说明与时间） | 已有；连接状态 T19b |
| 自定义工具 | 声明方式、命名规则与保留名的说明 | — | — | 自定义工具由业务系统在创建会话时声明（`POST /v1/sessions` 的 `custom_tools`），平台不做项目级登记；具体会话声明的工具在会话详情查看（`GET /v1/sessions/{id}` 的 `custom_tools`） | 已有 |

## 7 知识库

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 知识库列表 | 名称、Embedding 与 rerank 模型、文档数 | 新建 | 按需 | `GET/POST /v1/knowledge-bases`（K） | 已有 |
| 知识库设置 | 名称、说明、rerank 模型 | 修改、删除（确认对话框写明文档与切片一并删除） | 按需 | `PATCH /v1/knowledge-bases/{id}`、`DELETE /v1/knowledge-bases/{id}`（K） | T19b |
| 文档管理 | 文档、状态、页数、切片数、错误 | 上传（带 `acl_tags`）、删除、重新处理 | 有未完成文档时轮询 5 秒，全部完成后停止；完成时 toast | `POST/GET /v1/knowledge-bases/{id}/documents`、`GET/DELETE …/documents/{doc_id}`、`POST …:reprocess`（K） | 已有 |
| 检索测试 | 召回的切片、分数、来源 | 输入查询与过滤条件 | 按需 | `POST /v1/knowledge-bases/{id}/search`（K） | 已有 |

## 8 模型

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 供应商账号 | 名称、类型、端点、凭据、限流、最近一次连通性测试（`last_check`：`ok`、`auth_failed`、`unreachable`、`error`） | 新建、修改、删除、测试连通性 | 按需 | `GET/POST/PUT/DELETE T:/provider-accounts[/{id}]`、`POST T:/provider-accounts/{id}:test` | 已有；测试 T19b |
| 模型目录 | 模型、种类、上下文窗口、价格表 | 新建、修改、删除 | 按需 | `GET/POST/PUT/DELETE T:/models[/{id}]` | 已有 |
| 价格表 | 价格表与各版本的单价 | 新建、发布新版本 | 按需 | `GET/POST T:/price-books[/{id}]`、`GET/POST T:/price-books/{id}/versions` | 已有 |

## 9 待处理

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 待处理列表 | 本项目等待回应的动作：类型、工具与入参、建议规则（`suggested_rules`）、会话、智能体、截止时间 | 按类型、智能体、会话筛选；批准、拒绝（可选记住此决定，可选一条建议规则作为 `remember_rule`）、恢复确认 | 轮询 15 秒 | `GET /v1/pending-actions?status=pending&type=&agent_id=&session_id=`（R）；回应 `POST /v1/sessions/{id}/events`（R） | T19b（列表），已有（回应） |
| 历史 | 已回应与超时的动作：结论、来源（调用方、超时、中断、无人值守）、处理人、时间 | 按状态、类型、智能体筛选 | 按需 | `GET /v1/pending-actions?status=resolved|expired`（R） | T19b |

审批人只有 `sessions:read` 与 `actions:respond`：回应事件（`user.tool_confirmation`、`user.custom_tool_result`、`user.recovery_confirmation`）接受 `sessions:write` 或 `actions:respond`，其他事件仍要 `sessions:write`（ADR-0053）。

## 10 用量与预算

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 账单 | 租户或项目按天、模型、智能体、API Key 聚合的 token 与成本 | 切换维度与时间范围 | 按需 | `GET T:/usage`（`BUDGET_READ`）、`GET P:/usage`（`USAGE_READ`） | 已有 |
| 明细 | 账本记录：时间、种类、模型、会话、轮次、API Key、token、成本 | 按项目、种类、模型、会话、时间范围筛选；导出 CSV | 按需 | `GET T:/usage-records`、`GET T:/usage-records:export`（`BUDGET_READ`）；项目内 `GET P:/usage-records`、`GET P:/usage-records:export`（`USAGE_READ`） | T19b |
| 预算设置 | 四级预算与默认值、本周期已用 | 新建、修改、删除 | 按需 | `GET/POST/PUT/DELETE T:/budgets[/{id}]` | 已有 |
| 告警 | 软阈值告警 | — | 按需 | `GET T:/budget-alerts` | 已有 |
| 本项目的预算消耗 | 同概览 | — | 轮询 5 分钟 | `GET P:/budget-status` | T19b |

## 11 设置

| 区域 | 展示的数据 | 操作 | 实时性 | 接口 | 状态 |
|---|---|---|---|---|---|
| 成员与角色 | 成员（已加入、已邀请）、邮箱、角色绑定 | 按用户标识或邮箱添加成员、修改资料、移除；授予、撤销角色 | 按需 | `GET/POST T:/members`、`GET/PATCH/DELETE T:/members/{id}`、`GET/POST T:/role-bindings`、`DELETE T:/role-bindings/{id}` | 已有；邮箱邀请与 `status` T19b |
| 项目 | 项目列表 | 新建、改名、删除 | 按需 | `GET/POST T:/projects`、`GET/PATCH/DELETE T:/projects/{id}` | 已有 |
| API Key | Key 的前缀、作用域、限流、最后使用时间 | 创建（明文只显示一次）、改名、吊销 | 按需 | `GET/POST P:/api-keys`、`GET/PATCH P:/api-keys/{id}`、`POST P:/api-keys/{id}:revoke` | 已有 |
| Webhook | 地址、订阅的事件、状态 | 新建（签名密钥只显示一次）、修改、删除、轮换签名密钥 | 按需 | `GET/POST/PUT/DELETE P:/webhooks[/{id}]`、`POST P:/webhooks/{id}:rotate-secret` | 已有；轮换 T19b |
| 投递记录 | 每次投递的事件、状态、尝试次数、响应码 | 按 Webhook、会话、状态、时间范围筛选；重投 | 按需 | `GET /v1/webhook-deliveries?created_after=&created_before=`、`GET /v1/webhook-deliveries/{id}`、`POST …:redeliver`（R） | 已有；时间范围 T19b |
| 凭据 | 名称、版本、最后使用时间、引用方 | 写入、轮换、删除 | 按需 | `GET/POST T:/secrets`、`GET/DELETE T:/secrets/{id}`、`POST T:/secrets/{id}:rotate` | 已有 |
| 策略 | 租户与项目的权限规则 | 新增、删除 | 按需 | `GET/POST/DELETE T:/permission-rules`、`P:/permission-rules` | 已有 |
| 租户设置 | 租户提示词、联网搜索后端 | 修改 | 按需 | `GET/PUT T:/settings` | 已有 |
| 审计日志 | 操作、操作者、资源、时间、详情 | 按时间、操作、操作者、资源、项目、会话筛选 | 按需 | `GET T:/audit-logs` | 已有 |
| 平台管理（平台管理员） | 全部租户、配额 | 创建租户（指定首个所有者的用户标识或邮箱）、改配额 | 按需 | `GET/POST /admin/v1/tenants`、`PUT T:/quotas` | 已有；所有者按邮箱 T19b |

## 聚合的数据来源与刷新频率

概览与智能体页的聚合不在主路径上做额外写入：计数读带索引的现有表，失败率读后台作业汇总的小时表。

| 聚合 | 数据来源 | 刷新 |
|---|---|---|
| 运行中、等待回应的会话数 | `rt.sessions` 按状态计数，走部分索引 `sessions_live_status_idx`（只含 `running`、`requires_action`、`rescheduled` 的会话） | 每次请求实时计数 |
| 待处理动作数 | `rt.pending_actions` 中 `status = 'pending'` 的行，走部分索引 `pending_actions_project_pending_idx` | 每次请求实时计数 |
| 每个智能体使用中的会话数 | `rt.sessions` 中未终止、未删除的会话按 `agent_id` 计数（`sessions_agent_idx`） | 每次请求实时计数 |
| 按智能体的失败率 | 小时汇总表 `rt.agent_turn_stats`：后台作业 `TurnStatsRollup` 每分钟把水位之后结束的轮次（`completed`、`interrupted`、`failed`）按项目、智能体、小时累加；失败率 = `failed` ÷ 三者之和（`cancelled` 没有执行，不计） | 每分钟；响应带 `stats_updated_at`（水位） |
| 最近错误 | `rt.turns` 中 `status = 'failed'` 的轮次（部分索引 `turns_failed_idx`），错误码与信息取该轮的 `session.error` 事件 | 每次请求实时读 |
| 本周期预算消耗 | control-plane 的 `cp.budget_usage`（账本消费者入账时累加）与 `cp.budgets` 的项目级设置或默认值 | 账本入账后（秒级） |
| 用量趋势与明细 | control-plane 的账本 `cp.usage_records` | 账本入账后（秒级） |

## OIDC 下的成员

- **添加。** `POST T:/members` 给出 `subject`（IdP 中的用户标识，与配置的用户标识声明一致，默认 `sub`）或 `email` 之一，可以同时给。只给邮箱的成员状态为 `invited`、`subject` 为空；角色可以照常授予给它。
- **首次登录绑定。** 用户带 OIDC 令牌访问控制台（任一管理接口，控制台启动时先调 `GET /admin/v1/me`）时，control-plane 用令牌中经过验证的邮箱（`email` 声明，且 `email_verified` 为 `true`；声明名与是否要求验证可配置）查找各租户中同一邮箱、尚未绑定的成员，写入该用户的 `subject`，状态变为 `active`，写审计 `member.bound`。绑定后该成员已有的角色立即生效。邮箱比较不区分大小写。
- **冲突。** 同一租户中该用户已经是成员（之前按用户标识添加过）时，邀请的角色绑定合并到已有成员上，邀请记录删除。同一租户中同一邮箱只能有一条未绑定的邀请（409 `member.already_exists`）。
- **不做。** 不经 IdP 的管理接口查询用户目录、不发邀请邮件：控制台提示管理员把控制台地址告诉被邀请人。

## 不做的事项

- 控制台不管理上传的文件（`/v1/files` 只在 Playground 上传），文件的删除与保留期给 R2（D74）。
- 跨项目的待处理收件箱、预算报表的高级分析属于 M2。
- 自定义工具没有项目级登记（见「能力」）。
