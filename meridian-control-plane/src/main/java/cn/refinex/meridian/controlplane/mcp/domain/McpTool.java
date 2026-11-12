package cn.refinex.meridian.controlplane.mcp.domain;

import java.util.Objects;

/**
 * 探测到的一个 MCP 工具。
 *
 * @param name 服务端的工具名（不含前缀）
 * @param description 服务端给出的说明；没有时为空字符串
 */
public record McpTool(String name, String description) {

    /**
     * 创建工具。
     *
     * @throws NullPointerException 缺少工具名时
     */
    public McpTool {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
    }
}
