package cn.refinex.meridian.runtime.sandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 一段沙箱 vCPU 秒的用量记录，写入 outbox 的 {@code usage} 主题，由 control-plane 的消费者按价格表计价入账（架构设计「计量与预算」，
 * T16 定稿消费格式）。与模型用量（{@code ModelUsageMessage}）同一主题，以 {@code kind} 区分。消费方按 outbox 消息 ID 幂等。
 *
 * @param kind 固定为 {@value #KIND}
 * @param occurredAt 记录时间（计量终点）
 * @param tenantId 租户 ID；必填
 * @param projectId 项目 ID；必填
 * @param agentVersionId 会话绑定的智能体版本
 * @param sessionId 会话 ID；必填
 * @param threadId 计量时所在轮次的线程；空闲回收与删除会话时为空
 * @param turnId 计量时所在的轮次；空闲回收与删除会话时为空
 * @param sandboxId 沙箱资源名；必填
 * @param size 规格：{@code small}、{@code medium} 或 {@code large}；必填
 * @param vcpu 规格的 vCPU 数
 * @param periodStart 计量起点；必填
 * @param periodEnd 计量终点；必填
 * @param vcpuSeconds vCPU 秒，保留三位小数；必填
 */
public record SandboxUsageMessage(
        String kind,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String agentVersionId,
        String sessionId,
        String threadId,
        String turnId,
        String sandboxId,
        String size,
        int vcpu,
        Instant periodStart,
        Instant periodEnd,
        BigDecimal vcpuSeconds) {

    /** 沙箱 vCPU 秒用量的种类。 */
    public static final String KIND = "sandbox_vcpu_seconds";

    /**
     * 创建用量记录。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public SandboxUsageMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sandboxId, "sandboxId");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(vcpuSeconds, "vcpuSeconds");
    }
}
