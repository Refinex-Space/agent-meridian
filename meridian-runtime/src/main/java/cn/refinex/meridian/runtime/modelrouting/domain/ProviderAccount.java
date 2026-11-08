package cn.refinex.meridian.runtime.modelrouting.domain;

import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import java.util.Objects;

/**
 * 供应商账号：类型、端点、凭据引用与默认的限流参数（架构设计「数据架构」中 control-plane 的 {@code provider_accounts}）。
 *
 * @param providerAccountId 供应商账号 ID（{@code prv_…}）；必填
 * @param provider 供应商类型；必填
 * @param baseUrl 端点；可为空，表示该类型的默认端点
 * @param secretRef 凭据引用，交给 {@code SecretResolver} 解析；可为空，表示不需要凭据
 * @param rateLimit 账号下模型没有单独配置时使用的限流参数；必填
 */
public record ProviderAccount(
        String providerAccountId, Provider provider, String baseUrl, String secretRef, RateLimit rateLimit) {

    /**
     * 创建供应商账号。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ProviderAccount {
        Objects.requireNonNull(providerAccountId, "providerAccountId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(rateLimit, "rateLimit");
    }
}
