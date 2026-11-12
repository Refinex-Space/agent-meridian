package cn.refinex.meridian.controlplane.secret.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 凭据的元数据：不含值。值只以密文存在库中（{@code cp.secrets.ciphertext}），任何接口都读不出明文（架构设计「凭据」）。
 *
 * @param id 凭据 ID（{@code sec_…}）；必填
 * @param tenantId 所属租户；必填
 * @param projectId 所属项目；为空表示租户级凭据
 * @param name 名称；必填
 * @param version 版本，创建为 1，每次轮换加一
 * @param createdBy 创建者的主体标识；必填
 * @param createdAt 创建时间；必填
 * @param updatedAt 最近一次写入的时间；必填
 * @param lastUsedAt 最近一次被解析的时间；为空表示没有用过
 */
public record Secret(
        String id,
        String tenantId,
        String projectId,
        String name,
        int version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt) {

    /**
     * 创建元数据。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public Secret {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 能否被某个范围的资源引用或解析：租户级凭据对租户内全部范围可用，项目级凭据只对同一项目可用。
     *
     * @param tenant 引用方或调用方的租户
     * @param project 引用方或调用方的项目；为空表示租户级的引用方（供应商账号、租户设置）
     * @return 可用时为 true
     */
    public boolean usableBy(String tenant, String project) {
        if (!tenantId.equals(tenant)) {
            return false;
        }
        return projectId == null || projectId.equals(project);
    }
}
