package cn.refinex.meridian.security.servicejwt;

import java.util.Objects;

/**
 * 认证后的调用方服务。
 *
 * @param service 服务名，例如 {@code knowledge}；必填
 */
public record ServicePrincipal(String service) {

    /**
     * 创建主体。
     *
     * @throws NullPointerException 缺少服务名时
     */
    public ServicePrincipal {
        Objects.requireNonNull(service, "service");
    }
}
