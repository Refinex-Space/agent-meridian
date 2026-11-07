package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.common.context.Interaction;
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
 */
public record NewSession(
        String id,
        String agentVersionId,
        String environmentId,
        long environmentRevision,
        String endUserId,
        Interaction interaction) {

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
    }
}
