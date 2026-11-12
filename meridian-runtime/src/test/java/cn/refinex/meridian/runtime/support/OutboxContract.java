package cn.refinex.meridian.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.testsupport.outbox.OutboxSamples;
import tools.jackson.databind.json.JsonMapper;

/**
 * outbox 消息的契约断言（ADR-0048）：消息记录按 {@code OutboxWriter} 的序列化方式写成 JSON 后，必须与 {@code meridian-test-support} 中的
 * 样本一致（字段名、取值与类型）。control-plane 的消费者测试使用同一份样本。
 */
public final class OutboxContract {

    private static final JsonMapper JSON = JsonMappers.lenient();

    private OutboxContract() {}

    /**
     * 断言消息与样本一致。
     *
     * @param topic 主题
     * @param sample 样本名
     * @param message 消息记录
     */
    public static void assertMatches(String topic, String sample, Object message) {
        assertThat(JSON.readTree(JSON.writeValueAsString(message)))
                .as(topic + "/" + sample)
                .isEqualTo(JSON.readTree(OutboxSamples.read(topic, sample)));
    }
}
