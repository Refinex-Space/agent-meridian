package cn.refinex.meridian.controlplane.web;

import cn.refinex.meridian.common.context.PlatformContext;

/**
 * 管理请求的 {@code PlatformContext}：租户与项目取自管理 API 的路径（MVC 服务用 {@code ScopedValue} 绑定，编码规范「并发与响应式」），
 * 用例在调用仓储之前用它绑定上下文。
 */
public final class Scopes {

    private Scopes() {}

    /**
     * 租户级上下文。
     *
     * @param tenantId 租户 ID
     * @return 上下文
     */
    public static PlatformContext tenant(String tenantId) {
        return PlatformContext.builder().tenantId(tenantId).build();
    }

    /**
     * 项目级上下文。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @return 上下文
     */
    public static PlatformContext project(String tenantId, String projectId) {
        return PlatformContext.builder().tenantId(tenantId).projectId(projectId).build();
    }
}
