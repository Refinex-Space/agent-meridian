package cn.refinex.meridian.controlplane.tenant.domain;

import java.time.Instant;

/**
 * 租户（架构设计「租户层级」）：计费与管理的单位。
 *
 * @param id 租户 ID（{@code ten_…}）
 * @param name 名称，1 到 128 个字符
 * @param maxConcurrentTurns 并发配额：同时执行的轮次上限，至少 1
 * @param eventRetentionDays 会话事件的保留天数，至少 1
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 */
public record Tenant(
        String id, String name, int maxConcurrentTurns, int eventRetentionDays, Instant createdAt, Instant updatedAt) {}
