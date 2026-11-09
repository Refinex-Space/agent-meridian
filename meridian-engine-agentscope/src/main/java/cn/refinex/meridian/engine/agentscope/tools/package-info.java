/**
 * 平台工具（架构设计「平台工具」）：基类 {@code PlatformTool}（规则内容语法、{@code matchRule} 与内容级建议、账本与幂等键、
 * 文件系统绑定、入参 schema 规整），六个文件工具，{@code bash} 与命令分段，联网与知识检索工具，{@code spawn_subagent}，
 * 来源标签与工具提供者。工具在虚拟线程上执行、可以阻塞；文件与命令操作经 {@code call.filesystem()}。
 *
 * <p>不负责权限合成（runtime 的 {@code approval}）与 HTTP 调用的 SSRF 校验（runtime 的 {@code tooling} 经端口提供）。
 * 可以依赖 AgentScope 2.0.4、{@code meridian-engine-spi} 与 {@code meridian-common}。
 */
package cn.refinex.meridian.engine.agentscope.tools;
