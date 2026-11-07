---
paths:
  - "console/**"
---

# 控制台规则（精要）

完整规范：`docs/engineering/frontend-design.md`（设计系统、禁用清单、组件选型、状态与反馈、工程规范）。

- 颜色、间距、圆角、阴影、动效时长只能来自令牌；组件中禁止十六进制颜色、`rgb()`、Tailwind 任意色值。
- 字体只用 IBM Plex Sans、IBM Plex Sans SC、IBM Plex Mono（等宽只给 ID、代码、JSON、日志）。图标只用 Phosphor 与按其网格绘制的自研 SVG。
- 动效：唯一的编排动效是会话时间轴；其余动效只回应用户操作，150–250ms；尊重 `prefers-reduced-motion`。
- 写界面前对照"禁用清单"：无默认 shadcn 主题、无装饰渐变与玻璃拟态、无卡片墙、无四个大数字首页、无全大写小标签、无标题单词变色、无 emoji、无占位文案、无每区块说明小字。
- 每个数据区域有骨架（几何与最终布局一致，150ms 延迟、最短 300ms）、空态（带主操作）、错误态（problem+json、可复制 `request_id`、重试）、无权限态；禁止整页转圈。
- 通知按通知策略：结果在视野内不弹 toast；不可逆操作用 `DangerConfirmDialog` 写明后果。
- API 类型从 OpenAPI 生成，禁止手写；组件不直接 `fetch`；SSE 用基于 `fetch` 的客户端（`EventSource` 不能带 `Authorization`）。
- 先查"自研组件"表与 `src/components/` 再新建组件；`features/A` 不引用 `features/B` 的内部文件。
- 文案：中文、句子式、按用户概念命名、按钮写动作、错误写原因与下一步、不用感叹号。
- 交付前用 `ego-browser`（或 Playwright）在亮暗主题、1280 与 1920 宽度下截图评审，存到 `tmp/evidence/<任务编号>/`。
