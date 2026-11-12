package cn.refinex.meridian.runtime.tooling.domain;

import cn.refinex.meridian.common.context.PlatformContext;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次联网搜索的用量记录，写入 outbox 的 {@code usage} 主题，由 control-plane 的用量消费者入账（架构设计「计量与预算」中的搜索次数，
 * ADR-0048 第 3 条）。与模型用量同一主题，以 {@code kind} 区分；消费方按 outbox 消息 ID 幂等。
 *
 * @param kind 固定为 {@value #KIND}
 * @param occurredAt 搜索完成的时间
 * @param tenantId 租户 ID；必填
 * @param projectId 项目 ID
 * @param apiKeyId API Key ID；控制台用户与平台内部发起时为空
 * @param sessionId 会话 ID
 * @param threadId 线程 ID
 * @param turnId 轮次 ID
 * @param backend 搜索后端：{@code searxng} 或 {@code bocha}；必填
 * @param count 搜索次数，固定为 1
 */
public record SearchUsageMessage(
        String kind,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String apiKeyId,
        String sessionId,
        String threadId,
        String turnId,
        String backend,
        int count) {

    /** 搜索次数的用量种类。 */
    public static final String KIND = "search_calls";

    /**
     * 创建用量记录。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public SearchUsageMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(backend, "backend");
    }

    /**
     * 一次搜索的用量。
     *
     * @param context 调用的平台上下文
     * @param backend 搜索后端
     * @param at 完成时间
     * @return 用量记录
     */
    public static SearchUsageMessage of(PlatformContext context, String backend, Instant at) {
        return new SearchUsageMessage(
                KIND,
                at,
                context.tenantId(),
                context.projectId(),
                context.apiKeyId(),
                context.sessionId(),
                context.threadId(),
                context.turnId(),
                backend,
                1);
    }
}
