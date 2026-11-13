package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 新建会话的参数。租户与项目不在这里：会话仓储从 {@code PlatformContext} 取（架构设计「租户隔离」）。
 *
 * @param id 会话 ID（{@code ses_…}）；必填
 * @param agentVersionId 智能体版本 ID；必填
 * @param environmentId 环境 ID；必填
 * @param environmentRevision 环境修订号，从 1 开始
 * @param endUserId 终端用户标识；可为空，表示没有终端用户
 * @param interaction 值守方式；必填
 * @param endUserAttributes 终端用户属性；可为空，表示没有
 * @param metadata 调用方附加的元数据；可为空，表示没有
 * @param customTools 自定义工具声明；可为空，表示没有
 * @param agentId 智能体 ID（{@code agt_…}），取自解析出的版本；可为空（只有测试数据不带它）
 */
public record NewSession(
        String id,
        String agentVersionId,
        String environmentId,
        long environmentRevision,
        String endUserId,
        Interaction interaction,
        EndUserAttributes endUserAttributes,
        Map<String, String> metadata,
        List<CustomToolDeclaration> customTools,
        String agentId) {

    /**
     * 创建参数。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 环境修订号小于 1 时
     */
    public NewSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentVersionId, "agentVersionId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(interaction, "interaction");
        if (environmentRevision < 1) {
            throw new IllegalArgumentException("环境修订号必须从 1 开始：" + environmentRevision);
        }
        endUserAttributes = endUserAttributes == null ? EndUserAttributes.NONE : endUserAttributes;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        customTools = customTools == null ? List.of() : List.copyOf(customTools);
    }

    /**
     * 没有终端用户属性、元数据与自定义工具的会话参数。
     *
     * @param id 会话 ID
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @param endUserId 终端用户标识；可为空
     * @param interaction 值守方式
     * @return 参数
     */
    public static NewSession of(
            String id,
            String agentVersionId,
            String environmentId,
            long environmentRevision,
            String endUserId,
            Interaction interaction) {
        return new NewSession(
                id, agentVersionId, environmentId, environmentRevision, endUserId, interaction, null, null, null, null);
    }
}
