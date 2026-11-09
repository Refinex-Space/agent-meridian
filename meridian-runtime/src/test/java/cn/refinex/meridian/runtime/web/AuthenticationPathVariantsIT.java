package cn.refinex.meridian.runtime.web;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 路由能匹配到的路径写法，都要经过认证（R1 安全审查，ADR-0041）：WebFlux 按解码、去掉矩阵参数后的段路由，
 * 认证过滤器曾对原始路径做前缀比较，{@code /internal;a=b/…}、{@code /%69nternal/…} 不带凭据就能调用内部接口。
 * 经真实 HTTP 发出原始路径（不经客户端的路径规整）。
 */
class AuthenticationPathVariantsIT extends ApiTestBase {

    private static final String EGRESS_BODY = "{\"source_ip\":\"10.0.0.1\",\"target\":\"a.example.com:443\"}";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal;a=b/v1/egress:authorize",
                "/%69nternal/v1/egress:authorize",
                "/internal/v1;a=b/egress:authorize"
            })
    void internalEndpointsRejectUnauthenticatedPathVariants(String path) throws Exception {
        HttpResponse<String> response = post(path, EGRESS_BODY);

        assertThat(response.statusCode()).as(path).isEqualTo(401);
        assertThat(response.body()).contains("auth.unauthenticated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1;x=y/sessions", "/%76%31/sessions"})
    void publicEndpointsRejectUnauthenticatedPathVariants(String path) throws Exception {
        HttpResponse<String> response =
                post(path, "{\"agent\":\"" + AGENT + "\",\"environment\":\"" + ENVIRONMENT + "\"}");

        assertThat(response.statusCode()).as(path).isEqualTo(401);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
