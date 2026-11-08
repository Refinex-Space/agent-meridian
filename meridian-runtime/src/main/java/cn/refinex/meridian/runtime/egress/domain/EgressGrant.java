package cn.refinex.meridian.runtime.egress.domain;

import java.util.Objects;

/**
 * 按源 IP 解析出的出网授权：IP 当前属于哪个会话、该会话环境的白名单。runtime 按源 IP 缓存它 30 秒（架构设计「沙箱出网」），
 * 会话的 Pod IP 变化时立即淘汰。
 *
 * @param sessionId 会话 ID；必填
 * @param tenantId 租户 ID；必填
 * @param projectId 项目 ID；必填
 * @param environmentId 会话绑定的环境 ID；必填
 * @param environmentRevision 会话固化的环境修订号
 * @param allowlist 环境的白名单；必填
 */
public record EgressGrant(
        String sessionId,
        String tenantId,
        String projectId,
        String environmentId,
        long environmentRevision,
        EgressAllowlist allowlist) {

    /**
     * 创建授权。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public EgressGrant {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(allowlist, "allowlist");
    }

    /**
     * 判定一个目标。
     *
     * @param target 目标
     * @return 放行或不在白名单内
     */
    public EgressDecision decide(EgressTarget target) {
        EgressDecision.Reason reason = allowlist.permits(target.host())
                ? EgressDecision.Reason.ALLOWED
                : EgressDecision.Reason.NOT_ALLOWLISTED;
        return new EgressDecision(reason, sessionId, target);
    }
}
