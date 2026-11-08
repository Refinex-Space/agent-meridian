package cn.refinex.meridian.runtime.tooling.app;

import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.port.WebSearchPort;
import cn.refinex.meridian.runtime.tooling.domain.WebSearchSettings;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 一种联网搜索后端的适配器（架构设计「平台工具」：{@code web_search} 的后端按租户配置），实现在 {@code tooling.infra}。
 *
 * <p>失败时异常消息是给模型看的英文说明（{@code web_search} 工具原样放进错误结果），不含密钥。
 */
public interface WebSearchAdapter {

    /**
     * 适配的后端。
     *
     * @return 后端
     */
    WebSearchSettings.Backend backend();

    /**
     * 搜索。
     *
     * @param settings 租户的后端配置
     * @param apiKey API 密钥；后端不需要时为 null
     * @param query 查询文本
     * @param maxResults 最多返回的结果数，[1, 20]
     * @return 结果，至多 {@code maxResults} 条
     */
    Mono<List<WebSearchPort.Result>> search(
            WebSearchSettings settings, SecretValue apiKey, String query, int maxResults);
}
