package cn.refinex.meridian.runtime.tooling.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.port.WebSearchPort;
import cn.refinex.meridian.runtime.tooling.domain.WebSearchSettings;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * 博查 Web Search API 的适配器（选它而不是 Tavily：企业部署在国内，博查可直连、按国内合规提供服务；Tavily 需要跨境访问，ADR-0035）。
 *
 * <p>{@code POST {base}/v1/web-search}，{@code Authorization: Bearer <key>}，请求体 {@code query}、{@code count}、
 * {@code summary: true}、{@code freshness: noLimit}；结果取 {@code data.webPages.value[]} 的 {@code name}、{@code url}，摘要优先用
 * {@code summary}，没有时用 {@code snippet}。响应体中 {@code code} 不是 200 时按失败处理。
 */
public final class BochaSearchAdapter extends HttpWebSearchAdapter {

    @Override
    public WebSearchSettings.Backend backend() {
        return WebSearchSettings.Backend.BOCHA;
    }

    @Override
    public Mono<List<WebSearchPort.Result>> search(
            WebSearchSettings settings, SecretValue apiKey, String query, int maxResults) {
        Objects.requireNonNull(apiKey, "apiKey");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("count", maxResults);
        payload.put("summary", true);
        payload.put("freshness", "noLimit");
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(settings.baseUrl(), "/v1/web-search"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.reveal())
                .POST(HttpRequest.BodyPublishers.ofString(JsonMappers.lenient().writeValueAsString(payload)));
        return send(request).map(body -> results(body, maxResults));
    }

    private static List<WebSearchPort.Result> results(JsonNode body, int maxResults) {
        JsonNode code = body.get("code");
        if (code != null && code.isNumber() && code.asInt() != 200) {
            throw new IllegalStateException("the search backend rejected the request (code " + code.asInt() + ")");
        }
        List<WebSearchPort.Result> results = new ArrayList<>();
        for (JsonNode item : body.path("data").path("webPages").path("value")) {
            String url = text(item, "url");
            if (url.isEmpty()) {
                continue;
            }
            String title = text(item, "name");
            String summary = text(item, "summary");
            results.add(new WebSearchPort.Result(
                    title.isEmpty() ? url : title, url, summary.isEmpty() ? text(item, "snippet") : summary));
            if (results.size() == maxResults) {
                break;
            }
        }
        return List.copyOf(results);
    }
}
