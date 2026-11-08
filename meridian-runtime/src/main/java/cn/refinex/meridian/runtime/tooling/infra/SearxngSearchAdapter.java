package cn.refinex.meridian.runtime.tooling.infra;

import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.port.WebSearchPort;
import cn.refinex.meridian.runtime.tooling.domain.WebSearchSettings;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * 自建 SearXNG 的适配器：{@code GET {base}/search?q=…&format=json}，结果取 {@code results[]} 的 {@code title}、{@code url}、
 * {@code content}（实例要在 {@code settings.yml} 的 {@code search.formats} 中开启 {@code json}）。SearXNG 不需要密钥。
 */
public final class SearxngSearchAdapter extends HttpWebSearchAdapter {

    @Override
    public WebSearchSettings.Backend backend() {
        return WebSearchSettings.Backend.SEARXNG;
    }

    @Override
    public Mono<List<WebSearchPort.Result>> search(
            WebSearchSettings settings, SecretValue apiKey, String query, int maxResults) {
        String parameters = "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&format=json&pageno=1";
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(settings.baseUrl(), "/search" + parameters))
                .GET();
        return send(request).map(body -> results(body, maxResults));
    }

    private static List<WebSearchPort.Result> results(JsonNode body, int maxResults) {
        List<WebSearchPort.Result> results = new ArrayList<>();
        JsonNode items = body.path("results");
        for (JsonNode item : items) {
            String url = text(item, "url");
            if (url.isEmpty()) {
                continue;
            }
            String title = text(item, "title");
            results.add(new WebSearchPort.Result(title.isEmpty() ? url : title, url, text(item, "content")));
            if (results.size() == maxResults) {
                break;
            }
        }
        return List.copyOf(results);
    }
}
