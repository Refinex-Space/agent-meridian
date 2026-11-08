package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.model.OpenAiCompatibleClientFactory;
import cn.refinex.meridian.engine.agentscope.model.ProviderClients;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.model.RoutedModelInvoker;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatMessage;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRole;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolCallDelta;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.runtime.modelrouting.app.VectorModelService;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import cn.refinex.meridian.testsupport.model.ScriptedVectors;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 契约测试：runtime 的真实 OpenAI 兼容客户端（AgentScope {@code OpenAIChatModel} 加平台 HTTP 传输、{@code RoutedModel}、
 * {@code RoutedModelInvoker}、平台的 Embedding 与 rerank 客户端）对 {@link ScriptedOpenAiServer}，证明对 runtime 而言它与真实供应商
 * 没有区别：文本与工具调用能被解析，用量按供应商报告结算，首包之前的 5xx 被重试，首包之后的断流是可重试错误（T09b）。
 */
class ScriptedProviderContractIT {

    private static final String CHAT = "mdl_scripted";
    private static final String EMBEDDING = "mdl_scripted_embedding";
    private static final String RERANK = "mdl_scripted_rerank";

    @TempDir
    Path directory;

    private ScriptedOpenAiServer server;
    private ModelRoutingFixture fixture;

    @BeforeEach
    void start() {
        server = ScriptedOpenAiServer.builder().start();
        fixture = new ModelRoutingFixture(directory, server.baseUrl())
                .model(CHAT, "scripted", 32_000, 512)
                .vectorModel(EMBEDDING, "embedding", "scripted-embedding", null, 3)
                .vectorModel(RERANK, "rerank", "scripted-rerank", server.rerankBaseUrl(), 10);
    }

    @AfterEach
    void stop() {
        fixture.close();
        server.close();
    }

    @Test
    void textIsStreamedAndSettledWithTheReportedUsage() {
        RoutedModel model = fixture.routedModel(ModelSpec.of(CHAT), ModelRoutingFixture.fastRetry());

        List<ChatResponse> responses = call(model, user("#script:chat"));

        assertThat(responses).as("流式分块").hasSizeGreaterThan(2);
        assertThat(textOf(responses)).isEqualTo("你好，我是脚本化模型。这是一段固定的回答，用来验证普通对话的完整事件序列。");
        assertThat(responses.getLast().getUsage()).isNotNull();
        assertThat(fixture.usage()).singleElement().satisfies(usage -> {
            assertThat(usage).containsEntry("usage_source", "provider");
            assertThat(usage).containsEntry("model_id", CHAT);
            assertThat(usage)
                    .containsEntry(
                            "input_tokens", responses.getLast().getUsage().getInputTokens());
        });
    }

    @Test
    void toolCallsAreParsedByTheAgentScopeClient() {
        RoutedModel model = fixture.routedModel(ModelSpec.of(CHAT), ModelRoutingFixture.fastRetry());

        List<ChatResponse> responses = call(model, user("#script:tool-once"));

        List<ToolUseBlock> starts = responses.stream()
                .flatMap(response -> response.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .filter(block -> block.getName().equals("lookup_order"))
                .toList();
        assertThat(starts)
                .singleElement()
                .satisfies(block -> assertThat(block.getId()).startsWith("call_"));
        assertThat(responses.stream()
                        .map(ChatResponse::getFinishReason)
                        .filter(reason -> reason != null)
                        .toList())
                .contains("tool_calls");
    }

    @Test
    void theDirectInvokerMergesStreamedToolCallFragments() {
        RoutedModelInvoker invoker = new RoutedModelInvoker(
                fixture.gateway,
                new ProviderClients(List.of(new OpenAiCompatibleClientFactory(fixture.transport))),
                ModelRoutingFixture.fastRetry());

        List<ChatChunk> chunks = invoker.stream(new ChatRequest(
                        CHAT,
                        List.of(new ChatMessage(ChatRole.USER, "#script:tool-once", null, null)),
                        null,
                        null,
                        null))
                .contextWrite(PlatformContexts.write(direct()))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(chunks).isNotNull();
        List<ToolCallDelta> deltas =
                chunks.stream().flatMap(chunk -> chunk.toolCalls().stream()).toList();
        assertThat(deltas).extracting(ToolCallDelta::index).containsOnly(0);
        assertThat(deltas.getFirst().name()).isEqualTo("lookup_order");
        assertThat(deltas.stream().map(ToolCallDelta::argumentsDelta).collect(Collectors.joining()))
                .isEqualTo("{\"order_id\":\"o-1001\"}");
        assertThat(chunks.stream()
                        .map(ChatChunk::textDelta)
                        .filter(t -> t != null)
                        .collect(Collectors.joining()))
                .isEqualTo("我先查一下订单。");
        assertThat(chunks.stream().filter(chunk -> chunk.usage() != null)).hasSize(1);
        assertThat(fixture.usage()).singleElement().satisfies(usage -> {
            assertThat(usage.get("agent_version_id")).as("直通调用不经智能体").isNull();
            assertThat(usage.get("session_id")).as("直通调用没有会话").isNull();
        });
    }

    @Test
    void serverErrorsBeforeTheFirstChunkAreRetriedTwice() {
        RoutedModel model = fixture.routedModel(ModelSpec.of(CHAT), ModelRoutingFixture.fastRetry());

        assertThatThrownBy(() -> call(model, user("#script:error-before-first-chunk")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("503");

        assertThat(server.calls())
                .as("首次加 2 次重试")
                .hasSize(3)
                .allSatisfy(call -> assertThat(call.script()).isEqualTo("error-before-first-chunk"));
        assertThat(fixture.usage()).as("首包之前失败不写用量").isEmpty();
    }

    @Test
    void aBrokenStreamAfterTheFirstChunkIsRetryableWithoutRetrying() {
        RoutedModel model = fixture.routedModel(ModelSpec.of(CHAT), ModelRoutingFixture.fastRetry());

        assertThatThrownBy(() -> call(model, user("#script:error-after-first-chunk")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("响应中途断开");

        assertThat(server.calls()).as("首包之后不重试").hasSize(1);
        assertThat(fixture.usage())
                .singleElement()
                .satisfies(usage -> assertThat(usage).containsEntry("usage_source", "estimated"));
    }

    @Test
    void embeddingsAreBatchedAndMeteredPerProviderRequest() {
        VectorModelService vectors = fixture.vectors();
        List<String> inputs = List.of("一", "二", "三", "四", "五", "六", "七");

        VectorModelService.Embeddings result = vectors.embed(EMBEDDING, inputs, 16)
                .contextWrite(PlatformContexts.write(direct()))
                .block(Duration.ofSeconds(30));

        assertThat(result).isNotNull();
        assertThat(result.vectors()).hasSize(7);
        for (int i = 0; i < inputs.size(); i++) {
            assertThat(result.vectors().get(i)).as("顺序与输入一致").containsExactly(ScriptedVectors.embed(inputs.get(i), 16));
        }
        assertThat(server.calls()).as("单次上限 3 条，拆成 3 批").hasSize(3);
        assertThat(fixture.usage()).hasSize(3).allSatisfy(usage -> {
            assertThat(usage).containsEntry("kind", "embedding_tokens");
            assertThat(usage).containsEntry("purpose", "embedding");
            assertThat(usage).containsEntry("usage_source", "provider");
        });
        assertThat(result.inputTokens()).isEqualTo(7);
    }

    @Test
    void rerankUsesItsOwnEndpointAndIsMetered() {
        VectorModelService.Reranked result = fixture.vectors()
                .rerank(RERANK, "住宿标准", List.of("报销需要发票", "住宿标准为每晚 500 元"), null)
                .contextWrite(PlatformContexts.write(direct()))
                .block(Duration.ofSeconds(30));

        assertThat(result).isNotNull();
        assertThat(result.scores().getFirst().index()).isEqualTo(1);
        assertThat(server.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/compatible-api/v1/reranks"));
        assertThat(fixture.usage()).singleElement().satisfies(usage -> {
            assertThat(usage).containsEntry("kind", "rerank_tokens");
            assertThat(usage).containsEntry("input_tokens", result.inputTokens());
        });
    }

    private List<ChatResponse> call(RoutedModel model, Msg message) {
        return model.stream(List.of(message), null, null)
                .contextWrite(PlatformContexts.write(fixture.context("ses_" + Ids.newUlid())))
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    /** 直通接口的上下文：没有会话与轮次。 */
    private PlatformContext direct() {
        return PlatformContext.builder()
                .tenantId(fixture.tenantId)
                .projectId(fixture.projectId)
                .apiKeyId("key_fixture")
                .build();
    }

    private static Msg user(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String textOf(List<ChatResponse> responses) {
        return responses.stream()
                .flatMap(response -> response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }
}
