package cn.refinex.meridian.runtime.stream.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
}
