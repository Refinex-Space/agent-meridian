package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 读出的会话（{@code rt.sessions} 中调度、执行与公开 API 用到的列）。
 *
 * @param id 会话 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param agentVersionId 智能体版本 ID
 * @param environmentId 环境 ID
 * @param environmentRevision 环境修订号
 * @param endUserId 终端用户标识；可为空
 * @param interaction 值守方式
 * @param status 会话状态
 * @param lastSeq 已分配的最大事件序号，没有事件时为 0
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 * @param customTools 会话声明的自定义工具，每轮注入（架构设计「待处理动作」中的"自定义工具"）；没有时为空列表
 * @param endUserAttributes 终端用户属性；没有时为 {@link EndUserAttributes#NONE}
 * @param metadata 调用方附加的元数据；没有时为空映射
 * @param terminatedAt 归档或删除的时间；可为空，表示未终止
 * @param deletedAt 删除的时间；可为空，表示未删除
 * @param apiKeyId 创建会话的 API Key；控制台用户创建时为空。轮次的预算与用量计入它（ADR-0048）
 * @param agentId 绑定的智能体 ID；T19b 之前创建的会话为空（ADR-0053）
 */
public record Session(
        String id,
        String tenantId,
        String projectId,
        String agentVersionId,
        String environmentId,
        long environmentRevision,
        String endUserId,
        Interaction interaction,
        SessionStatus status,
        long lastSeq,
        Instant createdAt,
        Instant updatedAt,
        List<CustomToolDeclaration> customTools,
        EndUserAttributes endUserAttributes,
        Map<String, String> metadata,
        Instant terminatedAt,
        Instant deletedAt,
        String apiKeyId,
        String agentId) {

    /** 对集合做不可变复制。 */
    public Session {
        customTools = customTools == null ? List.of() : List.copyOf(customTools);
        endUserAttributes = endUserAttributes == null ? EndUserAttributes.NONE : endUserAttributes;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 是否已终止（归档或删除）：终止的会话只读，不再接受事件（架构设计「状态机」）。
     *
     * @return 终止时为 true
     */
    public boolean isTerminated() {
        return status == SessionStatus.TERMINATED;
    }

    /**
     * 是否已删除。
     *
     * @return 删除时为 true
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
