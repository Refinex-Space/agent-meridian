package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.common.context.Interaction;
import java.time.Instant;

/**
 * 读出的会话，只含调度与执行需要的列；其余列由使用它们的任务按需加入。
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
        Instant updatedAt) {}
