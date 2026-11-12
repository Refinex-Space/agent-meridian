package cn.refinex.meridian.controlplane.mcp.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 登记的 MCP 服务（ADR-0047），发布智能体版本时它的端点、传输、请求头凭据与副作用等级写进规格快照（engine-spi 的 {@code McpServerSpec}）。
 *
 * @param id MCP 服务 ID（{@code mcp_…}）
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param name 服务名，项目内唯一，工具名前缀 {@code mcp__{name}__} 中的部分
 * @param transport 传输：{@code streamable_http} 或 {@code sse}
 * @param url 服务端点
 * @param headerSecretIds 注入的请求头：头名 → 凭据 ID，凭据值是请求头的完整取值
 * @param sideEffect 服务级副作用等级；为空时按 {@code non_idempotent} 处理
 * @param toolSideEffects 按工具单独标注的副作用等级
 * @param tools 最近一次探测到的工具，按名字排序
 * @param probedAt 最近一次探测成功的时间
 * @param createdBy 登记者
 * @param createdAt 登记时间
 * @param updatedAt 最近一次修改或探测的时间
 */
public record McpServer(
        String id,
        String tenantId,
        String projectId,
        String name,
        String transport,
        String url,
        Map<String, String> headerSecretIds,
        String sideEffect,
        Map<String, String> toolSideEffects,
        List<McpTool> tools,
        Instant probedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 未标注副作用等级时采用的等级：平台不知道外部工具有没有副作用，按最保守的等级记账、不重放（ADR-0035）。 */
    public static final String DEFAULT_SIDE_EFFECT = "non_idempotent";

    /**
     * 创建 MCP 服务，映射按键排序、不可变。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public McpServer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(url, "url");
        headerSecretIds =
                headerSecretIds == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(headerSecretIds));
        toolSideEffects =
                toolSideEffects == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(toolSideEffects));
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 一个工具生效的副作用等级：单独标注的，否则服务级的，否则 {@value #DEFAULT_SIDE_EFFECT}。
     *
     * @param tool 工具名
     * @return 副作用等级
     */
    public String sideEffectOf(String tool) {
        return toolSideEffects.getOrDefault(tool, sideEffect == null ? DEFAULT_SIDE_EFFECT : sideEffect);
    }
}
