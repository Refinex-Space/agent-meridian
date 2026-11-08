package cn.refinex.meridian.runtime.tooling.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.port.WebSearchPort;
import cn.refinex.meridian.runtime.config.FileAgentConfigProvider;
import cn.refinex.meridian.runtime.tooling.app.TenantWebSearch;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code web_search} 的两个适配器（SearXNG、博查）与按租户切换（T10b-A02）：后端是 WireMock，租户设置来自
 * {@code kind: tenant_settings} 的 YAML（{@link FileAgentConfigProvider}），密钥经 {@link SecretResolver} 解析。
 */
class WebSearchAdaptersTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String BOCHA_KEY = "sk-bocha-test";

    @RegisterExtension
    static final WireMockExtension searxng = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @RegisterExtension
    static final WireMockExtension bocha = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path directory;

    private TenantWebSearch search;

    @BeforeEach
    void configureTenants() throws IOException {
        Files.writeString(directory.resolve("ten-searxng.yaml"), """
                kind: tenant_settings
                tenant_id: ten_searxng
                system_prompt: 内网租户
                web_search:
                  backend: searxng
                  base_url: %s
                """.formatted(searxng.baseUrl()));
        Files.writeString(directory.resolve("ten-bocha.yaml"), """
                kind: tenant_settings
                tenant_id: ten_bocha
                web_search:
                  backend: bocha
                  base_url: %s/
                  secret_id: BOCHA_API_KEY
                """.formatted(bocha.baseUrl()));
        SecretResolver secrets = id -> "BOCHA_API_KEY".equals(id)
                ? reactor.core.publisher.Mono.just(new SecretValue(BOCHA_KEY))
                : reactor.core.publisher.Mono.error(new NotFoundException("凭据不存在：" + id));
        FileAgentConfigProvider configs = new FileAgentConfigProvider(directory);
        search = new TenantWebSearch(configs, secrets, List.of(new SearxngSearchAdapter(), new BochaSearchAdapter()));
        searxng.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {"query":"agentscope","number_of_results":3,"results":[
                          {"title":"AgentScope","url":"https://example.com/as","content":"多智能体框架","engine":"bing"},
                          {"title":"","url":"https://example.com/untitled","content":""},
                          {"title":"第三条","url":"https://example.com/3","content":"不会返回"}]}""")));
        bocha.stubFor(post(urlPathEqualTo("/v1/web-search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {"code":200,"log_id":"l1","msg":null,"data":{"_type":"SearchResponse","webPages":{
                          "totalEstimatedMatches":2,"value":[
                            {"id":"1","name":"博查文档","url":"https://bocha.example/doc","snippet":"片段","summary":"完整摘要"},
                            {"id":"2","name":"只有片段","url":"https://bocha.example/2","snippet":"仅片段"}]}}}""")));
    }

    @Test
    void searxngTenantUsesTheSearxngBackend() {
        List<WebSearchPort.Result> results = searchAs("ten_searxng", "agentscope", 2);

        assertThat(results)
                .containsExactly(
                        new WebSearchPort.Result("AgentScope", "https://example.com/as", "多智能体框架"),
                        new WebSearchPort.Result("https://example.com/untitled", "https://example.com/untitled", ""));
        searxng.verify(getRequestedFor(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("agentscope"))
                .withQueryParam("format", equalTo("json")));
        bocha.verify(0, postRequestedFor(urlPathEqualTo("/v1/web-search")));
    }

    @Test
    void bochaTenantUsesTheBochaBackendWithTheResolvedKey() {
        List<WebSearchPort.Result> results = searchAs("ten_bocha", "报销制度", 5);

        assertThat(results)
                .containsExactly(
                        new WebSearchPort.Result("博查文档", "https://bocha.example/doc", "完整摘要"),
                        new WebSearchPort.Result("只有片段", "https://bocha.example/2", "仅片段"));
        bocha.verify(postRequestedFor(urlPathEqualTo("/v1/web-search"))
                .withHeader("Authorization", equalTo("Bearer " + BOCHA_KEY))
                .withRequestBody(equalToJson("""
                        {"query":"报销制度","count":5,"summary":true,"freshness":"noLimit"}""")));
        searxng.verify(0, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void failuresAreReportedInEnglishWithoutSecrets() {
        bocha.stubFor(post(urlPathEqualTo("/v1/web-search"))
                .willReturn(
                        aResponse().withStatus(401).withBody("{\"code\":401,\"msg\":\"bad key " + BOCHA_KEY + "\"}")));
        assertThatThrownBy(() -> searchAs("ten_bocha", "x", 3))
                .hasMessage("the search backend returned HTTP 401")
                .hasMessageNotContaining(BOCHA_KEY);

        bocha.stubFor(post(urlPathEqualTo("/v1/web-search"))
                .willReturn(aResponse().withBody("{\"code\":403,\"msg\":\"余额不足\",\"data\":null}")));
        assertThatThrownBy(() -> searchAs("ten_bocha", "x", 3)).hasMessageContaining("rejected the request (code 403)");

        assertThatThrownBy(() -> searchAs("ten_unconfigured", "x", 3))
                .hasMessage("web search is not configured for this organization");
    }

    private List<WebSearchPort.Result> searchAs(String tenantId, String query, int max) {
        PlatformContext platform = PlatformContext.builder()
                .tenantId(tenantId)
                .projectId("prj_test")
                .build();
        return search.search(query, max)
                .contextWrite(PlatformContexts.write(platform))
                .block(WAIT);
    }
}
