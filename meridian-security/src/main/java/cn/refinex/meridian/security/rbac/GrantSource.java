package cn.refinex.meridian.security.rbac;

import cn.refinex.meridian.security.oidc.ConsoleUser;
import java.util.Set;

/**
 * 角色绑定的来源（control-plane 实现）：一个用户在某个租户中的全部角色，加上平台管理员身份。
 *
 * <p>实现可以阻塞（查数据库），必须线程安全。
 */
public interface GrantSource {

    /**
     * 查找用户的角色绑定。
     *
     * @param user 控制台用户：主体标识，以及令牌中的组（按组判定平台管理员，ADR-0043）
     * @param tenantId 租户 ID；为空表示请求不针对某个租户，只返回平台级角色
     * @return 角色绑定；没有时为空集合
     */
    Set<Grant> grants(ConsoleUser user, String tenantId);
}
