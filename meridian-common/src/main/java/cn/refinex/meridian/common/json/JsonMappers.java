package cn.refinex.meridian.common.json;

import java.util.TimeZone;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 平台 API 的 Jackson 3 {@link JsonMapper} 工厂，统一 JSON 约定：字段 snake_case，时间为 RFC 3339 UTC
 * （架构设计「API 设计」中的「约定」）。
 *
 * <p>按用途区分对未知字段的处理：
 * <ul>
 *   <li>{@link #strict()}：入站，解析调用方提交的请求体。出现未知字段即失败，拼错的字段名不会被静默忽略。
 *   <li>{@link #lenient()}：出站，解析平台向外调用后收到的响应、读取已存储的 JSON。忽略未知字段，
 *       对方新增字段或历史数据多出字段时不会中断。
 * </ul>
 * 两者的序列化输出完全相同。AgentScope 内部使用的 Jackson 2 不受影响。
 *
 * <p>线程安全：返回的实例不可变，全局共享。
 */
public final class JsonMappers {

    private static final JsonMapper STRICT =
            builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static final JsonMapper LENIENT =
            builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private JsonMappers() {}

    /**
     * 入站用：未知字段即失败。
     *
     * @return 共享实例
     */
    public static JsonMapper strict() {
        return STRICT;
    }

    /**
     * 出站用：忽略未知字段。
     *
     * @return 共享实例
     */
    public static JsonMapper lenient() {
        return LENIENT;
    }

    /**
     * 返回带平台约定的构建器，供需要再定制的场景使用（例如 Spring 的 {@code JsonMapper} Bean）。
     * 未知字段策略由调用方决定。
     *
     * @return 新的构建器
     */
    public static JsonMapper.Builder builder() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // 时间写成 ISO 8601 文本而不是数字；带偏移的时间先换算到 UTC 再写出
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .enable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
                .enable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .defaultTimeZone(TimeZone.getTimeZone("UTC"));
    }
}
