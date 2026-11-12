package cn.refinex.meridian.testsupport.outbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 读取 outbox 消息的契约样本：类路径 {@code cn/refinex/meridian/testsupport/outbox/<主题>/<名称>.json}。 */
public final class OutboxSamples {

    /** 用量主题的样本名，与 {@code kind} 相同。 */
    public static final List<String> USAGE = List.of(
            "model_tokens", "embedding_tokens", "sandbox_vcpu_seconds", "search_calls", "knowledge_storage_gb_days");

    /** 审计主题的样本名，与 {@code action} 的点换成下划线相同。 */
    public static final List<String> AUDIT = List.of(
            "turn_finished",
            "tool_executed",
            "sandbox_command",
            "permission_decided",
            "pending_action_concluded",
            "session_deleted",
            "session_purged",
            "session_events_expired");

    private OutboxSamples() {}

    /**
     * 读取一个样本。
     *
     * @param topic 主题：{@code usage} 或 {@code audit}
     * @param name 样本名
     * @return JSON 文本
     */
    public static String read(String topic, String name) {
        String path = "/cn/refinex/meridian/testsupport/outbox/" + topic + "/" + name + ".json";
        try (InputStream in = OutboxSamples.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("没有这个样本：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
