package cn.refinex.meridian.engine.spi.port;

import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 联网搜索，供平台工具 {@code web_search} 使用，由 runtime 的 {@code tooling} 实现，后端按租户配置（博查、Tavily、
 * Brave、SearXNG），见架构设计「端口」「平台工具」。
 */
public interface WebSearchPort {

    /**
     * 搜索。
     *
     * @param query 查询文本
     * @param maxResults 最多返回的结果数，取值 [1, 20]
     * @return 搜索结果；没有时为空列表
     */
    Mono<List<Result>> search(String query, int maxResults);

    /**
     * 一条搜索结果。
     *
     * @param title 标题；必填
     * @param url 链接；必填
     * @param snippet 摘要；可为空字符串
     */
    record Result(String title, String url, String snippet) {

        /**
         * 创建搜索结果。
         *
         * @throws NullPointerException 缺少标题或链接时
         */
        public Result {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(url, "url");
            snippet = snippet == null ? "" : snippet;
        }
    }
}
