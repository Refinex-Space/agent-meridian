# console

React 19 单页应用：管理与调试控制台。T20 做了设计系统、组件与原型；T21 做了工程基座：OIDC 登录、TanStack Router 路由与应用外壳、openapi-fetch 客户端、TanStack Query、SSE 续传、权限、主题与端到端测试；T22 做了智能体与环境（列表、详情、编辑、版本对比、子智能体拓扑、修订历史），T23 做了能力（技能、MCP 服务、自定义工具）、模型（供应商账号、模型目录、价格表）与设置的凭据（ADR-0056），其余一级导航仍是空态，由 T24 至 T25b 实现。完整规范见 `docs/engineering/frontend-design.md`，依赖与版本见 ADR-0021，基座的约定见 ADR-0055。

## 命令

在仓库根目录用 `pnpm -C console <脚本>`，或在 `console/` 内直接 `pnpm <脚本>`。pnpm 版本由 `packageManager` 锁定（10.34.9）。

- 安装：`pnpm install --frozen-lockfile`
- 开发：先起后端 `scripts/dev/console-e2e-env.sh up`（Compose 的 app 与 auth profile：control-plane、runtime、knowledge、脚本化模型服务、Keycloak；runtime 的智能体配置与模型目录读 control-plane），再 `pnpm dev`，打开 `http://localhost:5173`（只能用 localhost:5173：Keycloak 只登记了它的回调地址），用 `dev-developer` / `meridian-dev` 登录；本机直接跑的服务也行（`run-control-plane.sh`、`run-runtime.sh --server.port=18080`），地址可用 `MERIDIAN_*_URL` 覆盖（`vite.config.ts` 的代理表）
- lint：`pnpm lint` = ESLint（含 `eslint-rules/meridian.js`）+ `scripts/check-css.mjs` + Prettier 检查 + `gen:api --check`；类型检查 `pnpm typecheck`；单元测试 `pnpm test`，单个 `pnpm exec vitest run <路径>`
- 构建：`pnpm build`（先 `gen:api`）；Storybook `pnpm storybook`（6006）、`pnpm build-storybook`；格式化 `pnpm format`
- 端到端（控制台本身）：`pnpm e2e:app`（`playwright.app.config.ts`：构建后 `vite preview` 在 5173，已有 `pnpm dev` 时复用它；全局准备建租户"T21 控制台验收"、两个项目与成员、脚本化模型服务的模型、技能与知识库；`e2e/app/seed.ts` 经管理 API 建智能体；要先起后端）。评审截图加 `EVIDENCE_DIR=../tmp/evidence/<任务>`（智能体与环境：`evidence-agents.spec.ts`；能力、模型与凭据：`evidence-capabilities.spec.ts`）。带 `@live` 标签的用例调用真实模型，只在 `E2E_LIVE=1` 时运行（`models-live.spec.ts`：要 `DASHSCOPE_API_KEY`，不录跟踪与截图）
- 端到端（Storybook 上）：`pnpm e2e`（截图基线、时间轴性能与动效、无障碍；评审截图 `evidence*.spec.ts` 只在设了 `EVIDENCE_DIR` 时运行）；首次先 `pnpm exec playwright install chromium`。截图基线 `pnpm e2e e2e/shell-baseline.spec.ts`；Linux 基线与 CI 一样在 Playwright 官方镜像中跑：`scripts/dev/console-playwright-linux.sh baseline [--update-snapshots]`
- 改了 `meridian-api/…/meridian-v1.yaml` 或 `meridian-admin-v1.yaml` 后：`pnpm gen:api`（`redocly.yaml` 同时生成 `src/lib/api/v1.d.ts` 与 `admin.d.ts`），提交生成的文件
- `scripts/dev/verify.sh` 运行 install、lint、typecheck、test、build-storybook；CI 的 `console` 作业另跑 build、截图基线与 `e2e:app`

## 目录

- `src/app/`：`main.tsx`（静默登录回调直接处理，其余按需加载 `bootstrap.tsx`：读 `/config.json`、登录、渲染）、`router.tsx`（路由树，`/projects/$projectId/<导航>`）、`query-client.ts`（Query 的统一配置）、`shell/`（`AppShell` 只负责呈现，`ShellLayout` 接入路由、身份、待处理计数与横幅；`nav.ts` 是一级导航）
- `src/features/<领域>/`：一级导航的页面，`routes/` 放路由组件，`index.ts` 导出；`agents/model/`（规格的 Zod schema、跨字段规则、字段锚点 `fields.ts`、差异 `diff.ts`、工具与规则语法 `tools.ts`），`agents/components/editor/`（编辑页的状态 `use-agent-editor.ts`、分组、规则编辑器、Monaco）；`capabilities/model/`（技能包的解析 `skill-package.ts`、MCP 表单 `mcp.ts`），`models/`（目录与测试 `api/queries.ts`），`settings/`（凭据：`SecretSelect` 给其他领域选择或就地写入凭据，经 `index.ts` 导出）
- `src/components/templates/`：四种页面模板 `ListPage`、`DetailPage`、`EditPage`（`EditSection`）、`Workbench`
- `src/components/domain/<组件>/`：领域组件（`status-badge/labels.ts` 是唯一的状态文字映射，`data-region/` 是区域级加载，`error-state/notify-error.tsx` 是错误通知）；`src/components/ui/`：基元与 `glyphs.ts`
- `src/lib/`：`api/`（`client.ts` 的 `adminApi()`、`v1Api(projectId)`、`unwrap`、`authorizedFetch`，`errors.ts` 的 `ApiError`，`connection.ts` 连接状态，生成类型与 `types.ts` 别名）、`auth/`（oidc-client-ts 的会话、`useAuth`）、`access/`（`meQuery`、`projectsQuery`、`accessQuery`、`useProject`、`usePermission`）、`sse/`（`runSessionStream`、`useSessionEvents`）、`search.ts`（搜索参数格式）、`theme.ts`、`tokens.ts`、`format.ts`
- `src/test/`：`setup.ts`、`clipboard.ts`、`stories.tsx`（`storyFrame`、`hover`、`focus`）、`router.tsx`（`renderWithRouter`、故事的 `withRouter`）
- `src/prototypes/`：T20c 原型（见下）；`mocks/`：MSW 处理器与夹具，只给 Storybook 与测试；`e2e/`：Playwright（`app/` 连接后端，其余在 Storybook 上）

## 新增页面

1. 路由：页面组件放 `src/features/<领域>/routes/`，经 `index.ts` 导出；在 `app/router.tsx` 的项目路由下加子路由（`staticData` 的 `nav` 高亮导航、`crumb` 是面包屑）。筛选条件放搜索参数：FilterBar 用 `useSearchFilters(defs)`，其他参数在路由的 `validateSearch` 中校验（值只有字符串与字符串数组）。
2. 页面选四种模板之一，对照 Storybook「原型」组与 console-api-map.md 的接口与实时性。
3. 数据：在 `features/<领域>/api/` 写查询与变更的 Hook。公开 API 用 `useProjectApi()`（带 `Meridian-Project`），管理 API 用 `adminApi()`，结果经 `unwrap`；查询键以 `['v1', projectId, …]` 或 `['admin', tenantId, projectId, …]` 开头；轮询给 `refetchInterval`（页面隐藏时自动停止）。类型只用生成的类型与 `types.ts` 的别名。
4. 加载与错误：每个数据区域一个 `DataRegion`（骨架与最终几何一致）加 `useSuspenseQuery`；变更失败默认发错误通知，结果就在视野里的（表单、行内）设 `meta: { notifyError: false }` 就地显示 `ErrorState`；空态用 `EmptyState`。
5. 权限：`usePermission('<操作类别>', '<按钮做的事>')` 展开给 `PermissionGate`（页面主操作 disable，行操作 hide，整块 explain）；不在前端判断角色。
6. 测试：Hook 与组件写 Vitest；关键路径写 `e2e/app/<领域>.spec.ts`（`openAs(page, 'developer' | 'viewer', url)`）。外壳与模板的截图基线不应变化；改了外壳或模板要更新 darwin 与 Linux 两份基线并在评审中说明。
7. 截图评审：`EVIDENCE_DIR=../tmp/evidence/<任务> pnpm e2e:app`，亮暗 × 1280、1920，存到 `tmp/evidence/<任务>/`；页面完成后删除对应的原型与故事。

## 原型

T22 至 T25b 的验收基准：页面成品与 Storybook「原型」组一致（布局、层级、状态、文案）。页面 `session-list/`、`session-detail/`、`pending-actions/`、`agent-edit/`、`overview/`，模板的例子 `agents/`、`environments/`；`shell/app-shell.tsx` 是正式外壳加原型身份夹具的适配器。运行时数据走 MSW 与生成类型；管理数据是 `fixtures/` 的视图模型，页面任务接入管理 API 时写映射。原型故事要等 MSW 数据，a11y 用例在 `e2e/a11y.spec.ts` 的列表里逐个登记就绪文字。

## 令牌与组件的使用规则

- 颜色只用 Tailwind 工具类（`bg-surface`、`text-ink-2`）。必须写在 `style` 里时用 `var(--palette-*)`，不用 `var(--color-*)`：后者在 `:root` 上已经解析，嵌套的 `data-theme` 对它无效
- 不用 `light-dark()`：构建时被 Lightning CSS 降级为在 `:root` 解析，开发与生产表现不一致
- 字号只有 `text-12/13/14/16/18/22/28`，字重只有 normal、medium、semibold；圆角 `rounded-control`（4）、`rounded-panel`（6）、`rounded-overlay`（8）；阴影只有 `shadow-overlay`，只给浮层；遮罩 `bg-scrim`；语法色 `code-*` 只给 CodeBlock 的 Shiki 主题（`var(--palette-code-*)`）
- 内外边距与间隙只用 0、px、1、2、3、4、6、8、12（lint 拒绝其他档位）；宽高不受限
- 新增令牌：改 `tokens.css` 两套调色板 → `lib/tokens.ts` 登记用途与对比度要求 → `lib/cn.ts` 的 tailwind-merge 登记名字；`tokens.test.ts` 校验两套值与对比度
- 图标：`<Icon glyph={…} size={16|20|24} />`，图形从 `@/components/ui/glyphs` 导入；Phosphor 只能在 `glyphs.ts` 中导入，新图标先在那里登记。当前项用 `weight="bold"`（自研图标的 bold 为 24 线宽，与 Phosphor 一致）
- 状态用 `StatusBadge`（`of="session|turn|tool|action|custom"`），不用带底色的胶囊徽标；文字与语义只改 `status-badge/labels.ts`
- 禁用样式写 `unavailable:`（原生 disabled 与 `aria-disabled="true"` 都命中），不写 `disabled:`：`PermissionGate` 用 aria-disabled 让无权限的按钮可聚焦并提示原因
- 禁用清单见 frontend-design.md；能机检的由 `meridian/*` 规则与 `check-css.mjs` 拦截，其余在截图评审中人工检查
- 目录分层由 `meridian/import-boundaries` 检查：ui → lib；domain → ui、lib；templates → domain；features → templates；`features/A` 只经 `features/B/index.ts` 引用 B；app 不依赖原型与 mocks；故事与测试另外可以导入 mocks 与 test

## 新增组件

1. 先查 frontend-design.md「自研组件」表与 `src/components/`；表里没有的先加一行。
2. 基元：不要在 `console/` 里直接 `shadcn add`（会覆盖已重塑的 `button.tsx`、装 `next-themes` 等包）。在会话临时目录放一份 `components.json` 与最小的 `tsconfig.json`（`@/*` 指向 `./src/*`），运行 `pnpm dlx shadcn@<ADR-0021 的版本> add <组件>`，把生成的源码按令牌重塑后放进 `src/components/ui/`；图标换成 `Icon` 与 `glyphs.ts`，文件头写明与上游的差异。
3. 领域组件：新建 `src/components/domain/<组件>/`，`index.ts` 导出；写 Vitest 测试覆盖行为。
4. 每个状态一个故事（默认、悬停、聚焦、禁用、加载、空、错误、长内容中适用的），外框用 `storyFrame`，伪类用 `hover(选择器)`、`focus(选择器)`；亮暗两套由工具栏切换。a11y 检查自动包含新故事；只有需要等数据加载的原型故事才手动加进 `e2e/a11y.spec.ts` 的列表。
5. `pnpm lint && pnpm typecheck && pnpm test`，需要时跑 `pnpm e2e`。

## 易错点

- Vitest 对样式表（含 `?raw`）一律返回空串：测试里读 CSS 用 `fs` 与 `import.meta.dirname`
- 虚拟滚动在 jsdom 中按 `clientHeight` 取视口、按 `offsetHeight` 量行高，组件测试要 mock 这两个属性
- Playwright 的 `reuseExistingServer` 会复用已在 6007 端口运行的预览，改了代码先 `pnpm build-storybook`；`vite preview` 要带 `--host 127.0.0.1`（macOS 上默认只监听 IPv6）
- Prettier 的 Tailwind 类名排序读 `src/styles/index.css`，改了令牌后先 `pnpm format`
- 时间轴性能按 Chrome 跟踪里每帧主线程忙碌时间衡量，不用 requestAnimationFrame 的间隔（60Hz 下恒为刷新周期），见 `e2e/timeline-performance.spec.ts`
- MSW 3 的 `worker.start()` 没有 `onUnhandledRequest`；Storybook 用 `msw-storybook-addon/csf3` 的 `mswLoader()`，故事在 `beforeEach({ msw })` 中登记处理器
- `@ibm/plex-sans-sc` 的 postinstall 是遥测上报，`pnpm-workspace.yaml` 不允许它执行
- TanStack Table 是 9：功能与行模型在 `tableFeatures` 中登记，状态读 `table.state`（不是 `getState()`），分页前的行是 `getPrePaginatedRowModel()`；数字列第一次点击是降序。查资料看 9 的文档
- Vitest 固定 `TZ=Asia/Shanghai`（`vite.config.ts`），时间格式的断言按东八区写
- jsx-a11y 不允许在 `ul`/`li` 上写 `tree`、`treeitem` 这类交互角色，用 `div`
- 故事里的伪类强制要给选择器：全局 `:focus-visible` 规则对任何元素都画轮廓，强制到所有元素会让外框也出现焦点环
- Radix 的选择器与模态菜单打开时只给外部加 aria-hidden，axe 判 aria-hidden-focus；`ui/inert-outside.tsx` 放在浮层内容里补上 inert。新写模态浮层（非 Dialog）照此处理
- 命令面板的空结果用 `CommandList` 的 `empty`，不要把空态放进 listbox；listbox 里只能有选项与分组
- 可滚动区域里没有可聚焦元素时要能用键盘聚焦：`ScrollArea` 给 `label`，`DialogBody`、`SheetBody` 已是 `role="region"` 加 `tabIndex={0}`
- `:read-only` 也匹配 `<button>`：只读样式写 `[&:is(input,textarea):read-only]:`，否则选择器的触发器一直是只读外观
- ECharts 不认 OKLCH：图表颜色在渲染时读 `--palette-*` 经 `lib/color.ts` 换成十六进制，主题切换时重绘（`overview/usage-chart.tsx`）
- Playwright 的 `getByText(...).first()` 可能命中 `PermissionGate` 给读屏的隐藏原因（`<span hidden>`），就绪文字不要用无权限的原因
- openapi-fetch 在创建客户端时取全局 `fetch`：单元测试先 `vi.stubGlobal('fetch', …)` 再 `vi.resetModules()` 与动态导入（`lib/api/client.test.ts`）
- 用到 Link、搜索参数的组件要在路由中渲染：Vitest 用 `renderWithRouter`（异步），故事用 `withRouter`；路由首次渲染是异步的，故事的 play 用 `findBy…`
- Playwright 认为 `aria-disabled` 的元素不可点击，验证"点了不生效"要 `click({ force: true })`
- 截图基线固定页面时钟（夹具按"现在"生成相对时间与日期）；基线文件名带平台后缀，CI 只认 `-linux`
- Radix 的选择器在关闭时也把内容渲染进文档片段：里面的 `InertOutside` 会一直挂载，它对其他浮层的 `data-aria-hidden` 也起作用（T22 修正为随标记撤销）
- 对话框关闭时焦点陷阱还在，页面其余部分是 inert：要把焦点放到页面上的字段，用 `DialogContent` 的 `onCloseAutoFocus`（`preventDefault` 后再聚焦），不要在关闭前聚焦
- 表单的错误统一经"锚点"（`features/agents/model/fields.ts`）：控件 id 是 `field-` 加路径（点号换成短横线），本地校验与服务端 `errors[].field` 映射到同一个控件；新增字段时给控件这个 id
- Monaco 0.57 的 exports 只开放 `monaco-editor/<路径>`：核心 `editor/editor.api`、功能 `features/<名>/register`、worker `editor/editor.worker?worker`；深路径与 CSS 直接导入会被拒绝
- 智能体与环境按路由懒加载（`app/router.tsx` 的 `lazy`）：外壳不要静态导入领域的 `index.ts`（模块顶层的 Zod 与 `lazy()` 让整个领域进首屏），需要时动态导入（命令面板的智能体）
- 环境与智能体页的权限都是 `agent_read`、`agent_write`（ADR-0047）；删除智能体后使用中的会话继续用已绑定的版本，后果文字按这一点写
- 表单里除提交按钮外的按钮都写 `type="button"`：`Button` 的原生默认是提交，选择器旁的"写入凭据"曾顺带提交了外层的供应商账号表单；嵌套对话框的 `onSubmit` 要 `stopPropagation`（React 事件沿组件树穿过 Portal 冒泡）
- 凭据值（明文）的输入框用 `autocomplete="new-password"`，写 `off` 时浏览器会把保存的登录名与密码填进去；写入与轮换不经 `useMutation`（变更缓存保留 `variables`），见 `settings/api/secrets.ts`
- Vitest 的 `beforeEach(() => mock.mockReset())` 会把返回的 mock 当成清理钩子调用：写成块体
- 截图用例拦截接口时把模式限定到 `/admin/v1/` 或 `/v1/`：`**/models?*`、`**/skills/<id>` 也会匹配页面地址本身（`/models?tab=…`），让整页加载失败

## 对外接口

只消费 `docs/engineering/console-api-map.md` 列出的接口；类型全部来自 `meridian-v1.yaml` 与 `meridian-admin-v1.yaml` 的生成类型，禁止手写。SSE 用 `lib/sse/session-stream.ts`（`fetch` 读取、`Last-Event-ID` 续传、按 seq 去重、乱序暂存、跳号重连补发、退避重连），请求经 `authorizedFetch(projectId)`。
