package cn.refinex.meridian.controlplane.catalog.app;

import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;

/**
 * 供应商的连通性探测（ADR-0053）：用账号的端点与凭据列一次模型清单，不产生用量。实现不抛异常，失败也作为结果返回。
 */
public interface ProviderProbe {

    /**
     * 探测一次。
     *
     * @param provider 供应商类型
     * @param baseUrl 端点；为空表示该类型的官方端点
     * @param apiKey 凭据明文；为空表示不带凭据
     * @return 结果
     */
    ConnectivityCheck probe(ProviderType provider, String baseUrl, String apiKey);
}
