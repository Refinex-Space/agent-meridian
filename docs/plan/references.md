# 参考源码

本机 `/Users/refinex/develop/project/reference/` 下有一批与本项目相关的开源实现，已通过 `.claude/settings.local.json` 授权为可读目录。它们是"可以查阅的经验"，不是"可以照搬的答案"：本项目的架构设计已经定下了方向，参考的目的是少走弯路、看清别人踩过的坑。

## 使用方法

**先定问题，再去看。** 带着具体问题查阅，例如"压缩摘要的提示词怎么写才能保留关键决定"，而不是泛泛地"看看别人怎么做"。

查到之后按三步处理，结论写进任务报告或相关 ADR：

1. **分析**：它解决的问题与我们的是否相同？它的前提（单机、单用户、本地文件系统、交互式终端）在我们的多租户服务端环境下是否成立？
2. **评估**：采纳、改造后采纳、不采纳，各给一句理由。与架构设计冲突的，不采纳。
3. **记录**：采纳或改造采纳的，在代码注释或提交说明里注明出处（仓库、路径、版本）；改造了什么、为什么。

**禁止**：

- 为了"参考过"而把不需要的东西加进来。
- 复制 claude-code-analysis 中的任何代码或提示词原文（见下表许可证一列）。
- 未注明出处地复制其他仓库中的非平凡代码。复制的代码必须符合原许可证要求。

## 仓库清单

| 仓库 | 路径 | 版本 | 许可证 | 适合查什么 | 注意 |
|---|---|---|---|---|---|
| AgentScope Java | `reference/agentscope-java` | 工作区是主干（2.1-SNAPSHOT）；**我们依赖的是 tag `v2.0.4`** | Apache-2.0 | 内核与 Harness 的真实 API、行为、源码细节 | 见下面"读 AgentScope 2.0.4" |
| Spring AI | `reference/spring-ai` | v2.0.2 | Apache-2.0 | Advisor 钩子设计、MCP Server 的 Servlet 传输用法、工具调用上限、结构化输出 | 架构设计已决定不依赖 Spring AI（ADR-0002），只参考设计 |
| Spring AI Agent Utils | `reference/spring-ai-agent-utils` | 主干 0.14.0-SNAPSHOT | Apache-2.0 | 文件、Grep、Glob、Shell 工具的参数设计与工具描述写法；Skills 加载；子智能体 Markdown 定义 | 单人主导的 0.x 项目，权限、会话、沙箱都缺失 |
| Claude Code 源码分析 | `reference/claude-code-analysis` | 2026-03 版本 | **无许可证** | Harness 机制：主循环、工具并发、三级压缩、权限判定顺序、Hooks、会话 JSONL、排队消息、子智能体、沙箱 | **只学机制，禁止复制任何代码和提示词原文**。来源为泄露的源码 |
| pi | `reference/pi` | v1.4.0 | MIT | 极简 Harness 的取舍；会话树 JSONL；压缩；扩展机制；精简的工具集与提示词 | 本地单用户设计 |
| Codex | `reference/codex` | 主干 | Apache-2.0 | 沙箱策略（macOS Seatbelt、Linux Landlock）、命令执行审批策略、补丁格式的编辑工具 | Rust 实现，只参考策略设计 |
| OpenCode | `reference/opencode` | 主干 | MIT | 服务端模式与 SSE 事件总线、会话与消息模型、供应商抽象、权限询问流程、Web 控制台 | TypeScript |
| ZCode | `reference/ZCode` | v3.15 之后 | Apache-2.0 | 中文 AI 编程工作台的桌面、Web 界面与后端会话管理；中文产品的界面文案与交互 | 参考交互，不照搬视觉 |
| DeepSeek Harness | `reference/deepseek-harness` | dsh-v0.1.2-alpha.1 | MIT | 插件化架构（一切皆插件）的扩展点设计 | 开发者预览版，变化快 |
| ThinkRail | `reference/thinkrail` | v0.3.0-nightly | Apache-2.0 | 围绕 pi 的智能体 IDE：工作区隔离、"活的规格"、技能复用；界面组织 | — |
| IntelliJ Community | `reference/intellij-community` | — | Apache-2.0 | 与本项目关系很小 | 体量巨大，不要全仓搜索 |

## 按主题的查阅指引

| 你在做 | 先看 |
|---|---|
| AgentScope 的任何 API | AgentScope 2.0.4（见下节） |
| 压缩摘要的提示词与保留策略 | claude-code-analysis（只看机制）、pi |
| 工具描述怎么写，模型才会正确使用 | spring-ai-agent-utils、pi |
| 权限规则的匹配语法与判定顺序 | claude-code-analysis（只看机制）、codex 的审批策略 |
| 沙箱与命令执行的安全策略 | codex、AgentScope 的沙箱扩展 |
| SSE 事件流、会话与消息的服务端模型 | opencode |
| MCP Server 的 Servlet 传输 | spring-ai 的 MCP 传输模块，以及 MCP Java SDK 0.17.2 的 sources jar |
| 控制台的信息组织与交互 | opencode 的 Web 控制台、ZCode、ThinkRail（只参考交互，不照搬视觉） |

## 读 AgentScope 2.0.4

本地仓库的工作区是主干，与 2.0.4 有明显差异（例如 `AgentSession`、`SessionLogStore`、`ToolSearchTool` 在 2.0.4 中都不存在）。读错版本会写出无法编译或行为不符的代码。按优先级：

1. **首选 sources jar**：T01 之后，执行 `./mvnw dependency:sources -pl meridian-engine-agentscope`，在 `~/.m2/repository/io/agentscope/` 下找到 `*-2.0.4-sources.jar`，用 `unzip -p <jar> <类路径>.java` 读取。这就是我们实际依赖的代码。
2. **其次 git tag**：`git -C /Users/refinex/develop/project/reference/agentscope-java show v2.0.4:<路径>`，搜索用 `git -C ... grep <模式> v2.0.4 -- <目录>`。
3. **不要**直接读工作区文件，除非已经确认该文件与 2.0.4 相同。

架构设计中引用的 AgentScope 类名和方法都已在 v2.0.4 上核对过；发现与架构设计描述不符时，以源码为准，并按执行协议的"决策分级"处理。
