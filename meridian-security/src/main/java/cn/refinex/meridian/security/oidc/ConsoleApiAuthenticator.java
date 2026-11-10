package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.apikey.ConsoleTokenAuthenticator;
import cn.refinex.meridian.security.apikey.PublicApiPrincipal;

/**
 * 公开 API 上的控制台用户认证（架构设计「认证」中"控制台用户"一行，ADR-0044）：校验 OIDC 令牌，按 {@code Meridian-Project} 查询
 * 用户在该项目上的作用域。项目不存在与没有角色同样是 403，不泄露项目是否存在。
 *
 * <p>方法阻塞，WebFlux 中经 {@code Blocking} 调用。线程安全：无状态。
 */
public final class ConsoleApiAuthenticator implements ConsoleTokenAuthenticator {

    /** 项目 ID 的长度上限，超长的头直接拒绝。 */
    static final int MAX_PROJECT_LENGTH = 64;

    private final ConsoleUserResolver users;
    private final ProjectAccessResolver access;
    private final int rateLimitPerMinute;

    /**
     * 创建认证器。
     *
     * @param users 控制台令牌的校验
     * @param access 项目权限的来源
     * @param rateLimitPerMinute 每个用户每分钟的请求上限
     */
    public ConsoleApiAuthenticator(ConsoleUserResolver users, ProjectAccessResolver access, int rateLimitPerMinute) {
        this.users = users;
        this.access = access;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    @Override
    public PublicApiPrincipal authenticate(String token, String project) {
        ConsoleUser user = users.resolve(token)
                .orElseThrow(() -> new MeridianException(ConsoleAuthErrorCode.INVALID_CONSOLE_TOKEN, "控制台令牌无效或已过期"));
        if (project == null || project.isBlank() || project.length() > MAX_PROJECT_LENGTH) {
            throw new MeridianException(
                    ConsoleAuthErrorCode.PROJECT_REQUIRED, "控制台用户调用公开 API 需要请求头 Meridian-Project: <项目 ID>");
        }
        ProjectAccess granted = access.resolve(user.subject(), project.strip())
                .filter(found -> !found.scopes().isEmpty())
                .orElseThrow(() -> new ForbiddenException(CommonErrorCode.FORBIDDEN, "当前用户在该项目中没有角色"));
        return new ConsoleUserPrincipal(
                user, granted.tenantId(), granted.projectId(), granted.scopes(), rateLimitPerMinute);
    }
}
