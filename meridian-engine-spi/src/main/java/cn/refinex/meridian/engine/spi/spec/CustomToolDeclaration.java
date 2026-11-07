package cn.refinex.meridian.engine.spi.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 业务方在创建会话时声明的自定义工具，由业务侧执行，见架构设计「待处理动作」中的"自定义工具"。
 *
 * <p>平台每轮把它作为只有 schema 的外部工具注入，智能体调用后本轮以 {@code TOOL_SUSPENDED} 结束，
 * 产生 {@code custom_tool_result} 待处理动作。与保留工具名的冲突由 API 校验拒绝。
 *
 * @param name 工具名，匹配 {@code ^[a-z][a-z0-9_]{0,63}$}；必填
 * @param description 给模型看的工具说明；必填
 * @param inputSchema 入参的 JSON Schema（object 类型）；必填
 * @param sideEffect 业务方声明的副作用等级；必填
 */
public record CustomToolDeclaration(
        String name, String description, Map<String, Object> inputSchema, SideEffect sideEffect) {

    /** 自定义工具名的格式，架构设计「待处理动作」规定。 */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /**
     * 创建自定义工具声明。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 工具名格式不合法时
     */
    public CustomToolDeclaration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(sideEffect, "sideEffect");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("自定义工具名不合法：" + name);
        }
        // JSON Schema 可能含 null 值（例如 default: null），Map.copyOf 会拒绝
        inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
}
