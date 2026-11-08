package cn.refinex.meridian.runtime.tooling.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.runtime.tooling.app.WebSearchAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 搜索后端适配器的公共部分：一个 JDK {@link HttpClient}（与 {@code ProviderVectorClient} 相同的做法）、超时与状态码处理、JSON 解析。
 * 错误消息是给模型看的英文说明，不含密钥与响应原文。
 */
abstract class HttpWebSearchAdapter implements WebSearchAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpWebSearchAdapter.class);

    /** 建立连接的超时。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 一次搜索请求的超时，小于 {@code web_search} 工具的等待上限（30 秒）。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final JsonMapper JSON = JsonMappers.lenient();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 发出请求并解析 JSON 响应。
     *
     * @param request 请求构建器（已设置地址、方法与业务请求头）
     * @return 响应 JSON
     */
    protected Mono<JsonNode> send(HttpRequest.Builder request) {
        HttpRequest built = request.timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return Mono.fromFuture(() -> client.sendAsync(built, HttpResponse.BodyHandlers.ofString()), false)
                .timeout(REQUEST_TIMEOUT.plusSeconds(1))
                .onErrorMap(error -> !(error instanceof IllegalStateException), error -> {
                    log.warn("联网搜索后端请求失败 backend={}", backend(), error);
                    return new IllegalStateException(
                            error instanceof TimeoutException
                                    ? "the search backend did not respond in time"
                                    : "the search backend is unreachable");
                })
                .map(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        log.warn("联网搜索后端返回错误状态 backend={} status={}", backend(), response.statusCode());
                        throw new IllegalStateException("the search backend returned HTTP " + response.statusCode());
                    }
                    try {
                        return JSON.readTree(response.body());
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("the search backend returned an invalid response");
                    }
                });
    }

    /**
     * 拼接后端根地址与路径。
     *
     * @param base 根地址
     * @param path 以 {@code /} 开头的路径
     * @return 完整地址
     */
    static URI endpoint(URI base, String path) {
        String root = base.toString();
        return URI.create((root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + path);
    }

    /**
     * 读取字符串字段。
     *
     * @param node 节点
     * @param field 字段名
     * @return 字段值；缺失或不是字符串时为空字符串
     */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? "" : value.asString();
    }
}
