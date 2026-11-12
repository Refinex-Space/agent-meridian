package cn.refinex.meridian.controlplane.agent.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 智能体的一个版本：草稿可修改，发布后不可变（架构设计「核心概念」）。
 *
 * @param id 智能体版本 ID（{@code agv_…}）
 * @param tenantId 所属租户
 * @param projectId 所属项目
 * @param agentId 所属智能体
 * @param version 版本号，按智能体从 1 递增
 * @param status 草稿或已发布
 * @param spec 规格 JSON（{@code schema/agent-spec.schema.json}）
 * @param createdBy 创建者
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 * @param publishedBy 发布者；草稿为空
 * @param publishedAt 发布时间；草稿为空
 */
public record AgentVersion(
        String id,
        String tenantId,
        String projectId,
        String agentId,
        int version,
        Status status,
        String spec,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        String publishedBy,
        Instant publishedAt) {

    /**
     * 创建版本。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public AgentVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(spec, "spec");
    }

    /**
     * 是否已发布。
     *
     * @return 已发布时为 true
     */
    public boolean published() {
        return status == Status.PUBLISHED;
    }

    /** 版本状态。 */
    public enum Status {
        /** 草稿：可修改、可删除。 */
        DRAFT("draft"),
        /** 已发布：不可变，会话可以绑定。 */
        PUBLISHED("published");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        /**
         * 返回库与接口中的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }

        /**
         * 按取值查找。
         *
         * @param value 取值
         * @return 状态
         * @throws IllegalArgumentException 取值未知时
         */
        public static Status of(String value) {
            for (Status status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("未知的版本状态：" + value);
        }
    }
}
