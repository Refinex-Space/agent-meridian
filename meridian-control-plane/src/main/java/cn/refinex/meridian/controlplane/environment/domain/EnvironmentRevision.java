package cn.refinex.meridian.controlplane.environment.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 环境的一个修订，不可变：会话固化修订号，运行中的会话按它读取配置。
 *
 * @param environmentId 环境 ID
 * @param revision 修订号
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param config 配置
 * @param createdBy 产生该修订的主体
 * @param createdAt 产生时间
 */
public record EnvironmentRevision(
        String environmentId,
        long revision,
        String tenantId,
        String projectId,
        EnvironmentConfig config,
        String createdBy,
        Instant createdAt) {

    /**
     * 创建修订。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public EnvironmentRevision {
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(config, "config");
    }
}
