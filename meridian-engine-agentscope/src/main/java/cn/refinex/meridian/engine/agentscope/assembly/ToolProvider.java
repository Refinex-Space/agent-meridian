package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.core.tool.AgentTool;
import java.util.List;

/**
 * 提供智能体可用的平台工具（含 MCP 工具），装配器把它们注册到传给 {@code HarnessAgent.Builder.toolkit(...)} 的
 * Toolkit 中（架构设计「平台工具」）。
 *
 * <p>生产实现由 T10a（文件工具）、T10b（联网、知识检索、MCP）、T12a（{@code bash}）、T13（{@code spawn_subagent}）补齐。
 * 平台工具必须继承 {@code ToolBase} 并实现 {@code matchRule}；实现按 {@link AgentProfile#tools()} 与环境档位决定
 * 提供哪些工具，例如 {@code bash} 只在 {@code sandbox} 档提供。
 */
@FunctionalInterface
public interface ToolProvider {

    /**
     * 返回要注册的工具与它们占用的资源。
     *
     * @param profile 装配对象
     * @return 工具与资源
     */
    ToolBundle tools(AgentProfile profile);

    /**
     * 一次装配得到的工具，以及随智能体实例关闭的资源（例如 MCP 客户端）。
     *
     * <p>资源由平台自己关闭：AgentScope 的 {@code Toolkit.copy()} 不转移 MCP 客户端的所有权，经
     * {@code Builder.toolkit(...)} 传入的工具包上注册的 MCP 客户端，{@code HarnessAgent.close()} 不会关闭。
     *
     * @param tools 工具；可为空列表
     * @param resources 实例关闭时按顺序关闭的资源；可为空列表
     */
    record ToolBundle(List<AgentTool> tools, List<AutoCloseable> resources) {

        /** 创建工具集合，对列表做不可变复制。 */
        public ToolBundle {
            tools = tools == null ? List.of() : List.copyOf(tools);
            resources = resources == null ? List.of() : List.copyOf(resources);
        }

        /**
         * 只有工具、没有资源的集合。
         *
         * @param tools 工具
         * @return 工具集合
         */
        public static ToolBundle of(List<AgentTool> tools) {
            return new ToolBundle(tools, List.of());
        }
    }
}
