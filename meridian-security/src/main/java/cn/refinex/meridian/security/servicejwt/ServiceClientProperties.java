package cn.refinex.meridian.security.servicejwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 服务间 HTTP 调用的配置（{@code meridian.security.service-client}，ADR-0042）。
 *
 * @param controlPlaneUrl control-plane 的基础 URL，例如 {@code http://control-plane:8080}；可为空，表示本服务不调用 control-plane
 *     （测试，以及 control-plane 自己）。设置后 API Key 经 control-plane 校验，服务 JWT 向它换取、公钥从它读取
 * @param connectTimeout 建立连接的超时，默认 2 秒：内部服务在同一集群，连不上就是不可用
 * @param readTimeout 读取响应的超时，默认 5 秒：内部接口都是数据库点查，超过它按依赖不可用处理
 */
@ConfigurationProperties("meridian.security.service-client")
public record ServiceClientProperties(
        String controlPlaneUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {

    /**
     * 是否配置了 control-plane 地址。
     *
     * @return 配置了非空地址时为 true
     */
    public boolean hasControlPlane() {
        return controlPlaneUrl != null && !controlPlaneUrl.isBlank();
    }
}
