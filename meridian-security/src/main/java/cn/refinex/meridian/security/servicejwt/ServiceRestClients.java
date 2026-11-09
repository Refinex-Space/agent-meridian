package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.security.web.RequestIds;
import java.net.http.HttpClient;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 服务间 HTTP 调用的唯一构造入口（编码规范「通用能力复用」）：连接与读取超时、出站服务凭据、请求 ID 的传递。
 *
 * <p>不做重试：调用方按自己的语义决定（例如 API Key 校验失败按依赖不可用返回 503，由业务方重试）。线程安全：创建后不可变，产出的
 * {@link RestClient} 可以共享。
 */
public final class ServiceRestClients {

    private final ServiceClientProperties properties;
    private final ServiceCredentialProvider credentials;

    /**
     * 创建构造器。
     *
     * @param properties 服务间调用的配置
     * @param credentials 出站服务凭据
     */
    public ServiceRestClients(ServiceClientProperties properties, ServiceCredentialProvider credentials) {
        this.properties = properties;
        this.credentials = credentials;
    }

    /**
     * 构造一个指向某个服务的客户端。每个请求在发出时取一次凭据（服务 JWT 会刷新），并带上当前线程 MDC 中的请求 ID。
     *
     * @param baseUrl 目标服务的基础 URL
     * @return 客户端
     */
    public RestClient create(String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(credentials.credential());
                    String requestId = MDC.get(RequestIds.MDC_KEY);
                    if (requestId != null && !request.getHeaders().containsHeader(RequestIds.HEADER)) {
                        request.getHeaders().set(RequestIds.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}
