# console

React 19 单页应用：管理与调试控制台。T20 阶段有工程、设计令牌、全部基元与领域组件（T20b）、`SessionTimeline` 与会话详情原型（Storybook + MSW）；路由、认证与真实 API 客户端在 T21。完整规范见 `docs/engineering/frontend-design.md`（「设计定稿」是令牌与自查的依据），依赖与版本见 ADR-0021。

## 命令

在仓库根目录用 `pnpm -C console <脚本>`，或在 `console/` 内直接 `pnpm <脚本>`。pnpm 版本由 `packageManager` 锁定（10.34.9），全局 pnpm 10 会自动切换。

- 安装：`pnpm install --frozen-lockfile`
- 开发：`pnpm dev`（入口直接展示会话详情原型，接口由 MSW 模拟，第 2 轮实时推送并停在等待批准）
- lint：`pnpm lint` = ESLint（含 `eslint-rules/meridian.js` 守护规则）+ `scripts/check-css.mjs` + Prettier 检查 + `gen:api --check`（生成类型是否过期）
- 类型检查：`pnpm typecheck`；单元测试：`pnpm test`（Vitest，含守护规则的 RuleTester 测试）；单个：`pnpm exec vitest run <路径>`
- Storybook：`pnpm storybook`（6006），构建 `pnpm build-storybook`（输出 `storybook-static/`）
- 端到端：`pnpm e2e`（Playwright，跑在构建后的 Storybook 上：时间轴性能、动效、无障碍）；首次先 `pnpm exec playwright install chromium`。`e2e/a11y.spec.ts` 从 `index.json` 读出全部故事逐个检查（亮暗各约 4 分钟），设了 `EVIDENCE_DIR` 时写出 `a11y-light.json`、`a11y-dark.json`
- 评审截图与录屏：`EVIDENCE_DIR=../tmp/evidence/<任务编号> pnpm e2e e2e/evidence.spec.ts`
- 改了 `meridian-api/…/meridian-v1.yaml` 后：`pnpm gen:api`，提交生成的 `src/lib/api/v1.d.ts`
- 格式化：`pnpm format`
- `scripts/dev/verify.sh` 运行 install、lint、typecheck、test、build-storybook；Playwright 不在其中

## 目录

- `src/styles/tokens.css`：令牌的唯一来源。`@theme inline static` 把颜色、阴影映射到 `--palette-*`；`:root, [data-theme='light']` 与 `[data-theme='dark']` 是两套调色板；字号、字重、圆角、动效在 `@theme static`。Tailwind 默认的色板、字号、圆角、阴影、字距全部移除
- `src/lib/`：`api/`（生成类型 `v1.d.ts` 与别名 `types.ts`、会话请求）、`sse/`（解析、订阅、正在生成的块、`useSessionEvents`）、`tokens.ts`（颜色令牌清单与对比度要求）、`color.ts`、`format.ts`、`cn.ts`
- `src/components/ui/`：基元（shadcn 源码按令牌重塑，文件头写明与上游的差异；`field-styles.ts` 是输入类控件共用的外观）、`icon.tsx` 与 `glyphs.ts`（图标清单）。组件清单见 frontend-design.md「自研组件」，每个组件旁边是它的 `*.stories.tsx`
- `src/components/domain/<组件>/`：领域组件，一个目录一个，经 `index.ts` 导出，旁边是故事与 Vitest 测试。`session-timeline/` 的 `model.ts` 是事件到行、线、分叉的纯函数，界面只读它；`status-badge/labels.ts` 是全控制台唯一的状态文字与语义映射；`data-table/features.ts` 登记 TanStack Table 9 的功能与列元数据；`filter-bar/use-url-filters.ts` 是筛选与 URL 的同步（T21 换成路由的搜索参数）
- `src/test/`：Vitest 的 `setup.ts`、`clipboard.ts`（可断言的剪贴板），故事的公共外框与伪类参数 `stories.tsx`（`storyFrame`、`hover`、`focus`）
- `src/assets/icons/`：自研 SVG（256 网格、16 线宽），经 SVGO、SVGR 生成组件，`index.tsx` 包成与 Phosphor 相同的接口
- `src/prototypes/`：页面原型与应用外壳原型（T21 替换）；`mocks/`：MSW 处理器与夹具（`fixtures/q3-session.ts` 原型会话，`large-session.ts` 固定的 1 万条事件，`session-list.ts` 固定的会话列表）
- `components.json`：shadcn CLI 的配置，只用于 `shadcn add --dry-run`、`--view` 比对上游；生成新基元见下面的「新增组件」
- `eslint-rules/`：本地 ESLint 插件；`e2e/`：Playwright

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
- 目录分层由 `meridian/import-boundaries` 检查：ui → lib；domain → ui、lib；`features/A` 只经 `features/B/index.ts` 引用 B；故事与测试另外可以导入 mocks

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

## 对外接口

只消费 `docs/engineering/console-api-map.md` 列出的接口；类型全部来自 `meridian-api` 的 `meridian-v1.yaml`（管理 API 的类型在 T21 生成），禁止手写。SSE 用基于 `fetch` 的 `lib/sse/session-stream.ts`（`Last-Event-ID` 续传、按 seq 去重、退避重连）。
