package cn.refinex.meridian.runtime.stream.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 平台事件内容（{@code session_events.data}）与轮次输入（{@code turn_inputs.payload}）的 JSON 编解码（ADR-0027）。
 *
 * <p>约定在 {@code common.json} 的基础上增加三条，使存储格式与对外 API 的 {@code data} 一致：
 * <ul>
 *   <li>枚举写成小写的常量名，与 engine-spi 中各枚举的 {@code value()} 相同（{@code EngineJsonTest} 逐个核对），读取时不区分大小写。
 *   <li>{@link MessageContent} 带判别字段 {@code type}：{@code text}、{@code image}、{@code file}。
 *   <li>没有字段的事件（例如 {@code session.status_running}）写成 {@code {}}。
 * </ul>
 * 事件本身的类型不写进内容，由 {@code session_events.type} 给出；轮次输入的具体类型由 {@code turns.kind} 给出。
 * 读取时忽略未知字段，历史数据多出字段不会失败。
 *
 * <p>线程安全：映射器不可变，全局共享。
 */
public final class EngineJson {

    private static final JsonMapper MAPPER = JsonMappers.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .addMixIn(MessageContent.class, MessageContentMixIn.class)
            .addMixIn(SessionEvent.Usage.class, OmitNullsMixIn.class)
            .build();

    private EngineJson() {}

    /**
     * 把事件内容写成 JSON，即事件信封的 {@code data}。
     *
     * @param event 事件
     * @return JSON 文本
     */
    public static String writeEvent(EngineEvent event) {
        return MAPPER.writeValueAsString(event);
    }

    /**
     * 按事件类型解析事件内容。
     *
     * @param type 事件类型，即 {@code session_events.type}
     * @param json 事件内容 JSON
     * @return 事件
     */
    public static EngineEvent readEvent(EventType type, String json) {
        return MAPPER.readValue(json, type.eventClass());
    }

    /**
     * 把轮次输入写成 JSON。
     *
     * @param input 轮次输入
     * @return JSON 文本
     */
    public static String writeInput(TurnInput input) {
        return MAPPER.writeValueAsString(input);
    }

    /**
     * 按轮次类型解析轮次输入。
     *
     * @param kind 轮次类型，即 {@code turns.kind}
     * @param json 轮次输入 JSON
     * @return 轮次输入
     * @throws IllegalArgumentException 轮次类型未知时
     */
    public static TurnInput readInput(String kind, String json) {
        Class<? extends TurnInput> type = switch (kind) {
            case "messages" -> TurnInput.NewMessages.class;
            case "confirmations" -> TurnInput.Confirmations.class;
            case "custom_tool_results" -> TurnInput.CustomToolResults.class;
            case "recovery" -> TurnInput.RecoveryOnly.class;
            default -> throw new IllegalArgumentException("未知的轮次类型：" + kind);
        };
        return MAPPER.readValue(json, type);
    }

    /**
     * 解析会话声明的自定义工具（{@code sessions.custom_tools}，JSON 数组）。
     *
     * @param json JSON 数组；为空表示没有声明
     * @return 自定义工具声明
     */
    public static List<CustomToolDeclaration> readCustomTools(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return List.of(MAPPER.readValue(json, CustomToolDeclaration[].class));
    }

    /**
     * 把自定义工具声明写成 JSON 数组。
     *
     * @param tools 自定义工具声明
     * @return JSON 数组
     */
    public static String writeCustomTools(List<CustomToolDeclaration> tools) {
        return MAPPER.writeValueAsString(tools);
    }

    /**
     * 按同样的约定把值写成 JSON，用于待处理动作的结论、恢复说明等随事件与输入一起存储的值。
     *
     * @param value 值
     * @return JSON 文本
     */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * 按同样的约定解析 {@link #write(Object)} 写出的 JSON。
     *
     * @param json JSON 文本
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 值
     */
    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /**
     * 把已解析的树结构（例如 YAML 读出的映射）按同样的约定转换为 engine-spi 的类型，开发配置用它读取智能体版本与环境。
     *
     * @param tree 映射、列表与标量组成的树
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 转换结果；构造函数的校验失败时抛出对应的异常
     */
    public static <T> T convert(Object tree, Class<T> type) {
        return MAPPER.convertValue(tree, type);
    }

    /** 返回底层映射器，供同包的测试核对枚举与内容块的写法。 */
    static JsonMapper mapper() {
        return MAPPER;
    }

    /** {@link MessageContent} 的多态映射：内容块带判别字段 {@code type}。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = MessageContent.Text.class, name = "text"),
        @JsonSubTypes.Type(value = MessageContent.Image.class, name = "image"),
        @JsonSubTypes.Type(value = MessageContent.FileRef.class, name = "file")
    })
    private interface MessageContentMixIn {}

    /** 没有值的字段不写出：{@code session.usage} 的 {@code model_id} 在本轮没有模型调用时不出现（ADR-0050）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private interface OmitNullsMixIn {}
}
