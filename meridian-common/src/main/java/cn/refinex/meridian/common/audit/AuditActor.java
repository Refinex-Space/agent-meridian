package cn.refinex.meridian.common.audit;

import java.util.Objects;

/**
 * 审计消息中的操作者（ADR-0048 第 6 条），序列化为 {@code {"type": …, "subject": …}}。
 *
 * @param type 类型：{@value #CONSOLE_USER}、{@value #API_KEY} 或 {@value #SERVICE}
 * @param subject 控制台用户的主体标识、API Key ID 或服务名
 */
public record AuditActor(String type, String subject) {

    /** 控制台用户。 */
    public static final String CONSOLE_USER = "console_user";

    /** API Key。 */
    public static final String API_KEY = "api_key";

    /** 平台服务（例如 runtime 的权限引擎、control-plane 的预算告警）。 */
    public static final String SERVICE = "service";

    /** {@code PlatformContext.actor()} 中控制台用户的前缀（ADR-0044）。 */
    private static final String USER_PREFIX = "user:";

    /**
     * 创建操作者。
     *
     * @throws NullPointerException 缺少类型或主体时
     */
    public AuditActor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
    }

    /**
     * 平台服务。
     *
     * @param name 服务名，例如 {@code runtime}
     * @return 操作者
     */
    public static AuditActor service(String name) {
        return new AuditActor(SERVICE, name);
    }

    /**
     * 控制台用户。
     *
     * @param subject 主体标识
     * @return 操作者
     */
    public static AuditActor consoleUser(String subject) {
        return new AuditActor(CONSOLE_USER, subject);
    }

    /**
     * 由 {@code PlatformContext.actor()} 的取值还原：{@code user:} 开头的是控制台用户，其余是 API Key ID。
     *
     * @param principal 请求主体
     * @return 操作者
     */
    public static AuditActor ofPrincipal(String principal) {
        Objects.requireNonNull(principal, "principal");
        return principal.startsWith(USER_PREFIX)
                ? consoleUser(principal.substring(USER_PREFIX.length()))
                : new AuditActor(API_KEY, principal);
    }
}
