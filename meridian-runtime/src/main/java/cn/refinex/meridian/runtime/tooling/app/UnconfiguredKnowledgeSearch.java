package cn.refinex.meridian.runtime.tooling.app;

import cn.refinex.meridian.engine.spi.port.KnowledgeSearchPort;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 知识库还没有接入时的 {@link KnowledgeSearchPort}：每次检索都以"未配置"的错误结束，{@code knowledge_search} 把它作为错误结果交给模型，
 * 不中断本轮（T10b-A04）。T19 换成经 HTTP 调用 knowledge 服务的实现。
 */
public final class UnconfiguredKnowledgeSearch implements KnowledgeSearchPort {

    /** 交给模型的说明。 */
    static final String MESSAGE = "knowledge base search is not configured on this platform yet; answer without it "
            + "or tell the user that the knowledge base is unavailable";

    @Override
    public Mono<List<Hit>> search(Query query) {
        return Mono.error(new IllegalStateException(MESSAGE));
    }
}
