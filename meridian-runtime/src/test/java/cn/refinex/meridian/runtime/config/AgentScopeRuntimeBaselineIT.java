package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.vertx.VertxHttpClientFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * M0 验证项 P1（T03-A06）：Spring Boot 4.1.2、JDK 25 下，最小的 WebFlux 应用上下文用平台的 Jackson 3 约定做 Web
 * 序列化，同一个 JVM 中用 {@code AgentAssembler}（经 {@code AgentInstanceCache}）装配 {@code HarnessAgent}，
 * 由 {@code ScriptedModel} 驱动一次包含工具调用的 {@code streamEvents}；AgentScope 内部使用 Jackson 2。
 *
 * <p>另外回应登记事项 D03：Spring Boot BOM 把 fabric8 Vert.x HTTP 客户端下的 Netty 从 4.1 提升到 4.2，
 * 这里用 AgentScope Kubernetes 扩展使用的同一个客户端（{@code VertxHttpClientFactory}）向本服务发起真实请求。
 * 报告见 {@code docs/poc/P01-运行基线.md}。
 */
@SpringBootTest(
        classes = AgentScopeRuntimeBaselineIT.BaselineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AgentScopeRuntimeBaselineIT {

    private static final String USER = "prj_p1:_";

    @Autowired
    private WebTestClient webTestClient;

    /** 在 Spring 上下文启动之前安装，Netty 事件循环与 Reactor 的非阻塞线程上发生阻塞调用即报错。 */
    @BeforeAll
    static void installBlockHound() {
        BlockHound.install();
    }

    @Autowired
    private JsonMapper jsonMapper;

    @LocalServerPort
    private int port;

    @Test
    void webfluxEndpointRunsAnAgentTurnWithToolCallAndSerializesWithPlatformJackson3() {
        webTestClient
                .post()
                .uri("/p1/agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"thread_id\": \"thr_p1_tool\", \"prompt\": \"查一下苹果\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                // snake_case 字段名证明响应由平台的 JsonMappers（Jackson 3）序列化
                .jsonPath("$.answer")
                .isEqualTo("工具返回了：echo:苹果")
                .jsonPath("$.tool_calls[0]")
                .isEqualTo("echo")
                .jsonPath("$.event_types")
                .value(types -> assertThat(types.toString())
                        .contains("TOOL_CALL_START", "TOOL_CALL_END", "TOOL_RESULT_END", "AGENT_RESULT"))
                .jsonPath("$.finished_at")
                .value(value -> assertThat(value.toString()).matches("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z"));
    }

    @Test
    void inboundJsonIsStrictAboutUnknownFields() {
        webTestClient
                .post()
                .uri("/p1/agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"thread_id\": \"thr_p1_strict\", \"prompt\": \"你好\", \"promt\": \"拼错的字段\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void jackson2AndJackson3CoexistInTheSameJvm() {
        assertThat(jsonMapper).as("WebFlux 使用平台的 Jackson 3 映射器").isSameAs(JsonMappers.strict());
        assertThat(tools.jackson.databind.cfg.PackageVersion.VERSION.getMajorVersion())
                .isEqualTo(3);
        assertThat(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString())
                .as("AgentScope 2.0.4 使用的 Jackson 2")
                .startsWith("2.21.");
    }

    @Test
    void fabric8VertxHttpClientWorksOnNetty42() throws Exception {
        assertThat(io.netty.util.Version.identify().get("netty-common").artifactVersion())
                .startsWith("4.2.");
        HttpClient client = new VertxHttpClientFactory().newBuilder().build();
        try {
            HttpResponse<String> response = client.sendAsync(
                            client.newHttpRequestBuilder()
                                    .uri("http://127.0.0.1:" + port + "/p1/ping")
                                    .build(),
                            String.class)
                    .get(30, TimeUnit.SECONDS);

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"ok\"");
        } finally {
            client.close();
        }
    }

    @Test
    void blockHoundIsActiveOnReactorNonBlockingThreads() {
        Mono<Object> sleeping = Mono.fromCallable(() -> {
                    Thread.sleep(1);
                    return new Object();
                })
                .subscribeOn(Schedulers.parallel());

        assertThatThrownBy(sleeping::block).hasCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    void agentTurnSubscribedOnNonBlockingThreadDoesNotBlock() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "梨")))
                .respond(r -> r.text("完成", 1))
                .build();
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(StaticToolProvider.always(RecordingTool.echo("echo")))
                .build();
        HarnessAgent agent = assembly.assembler()
                .assemble(AgentProfile.main(TestSpecs.agent().build(), TestSpecs.noneEnvironment()))
                .agent();
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("thr_p1_parallel")
                .userId(USER)
                .build();

        List<AgentEvent> events = agent.streamEvents(List.of(new UserMessage("并行线程上执行")), ctx)
                .subscribeOn(Schedulers.parallel())
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(events)
                .as("整轮（推理、工具执行、状态保存）在 Reactor 非阻塞线程上执行，BlockHound 没有报错")
                .anyMatch(AgentResultEvent.class::isInstance);
    }

    /** P1 入口的请求体。 */
    record RunRequest(String threadId, String prompt) {}

    /** P1 入口的响应体。 */
    record RunResult(String answer, List<String> toolCalls, List<String> eventTypes, Instant finishedAt) {}

    /**
     * 最小的 WebFlux 应用：只导入 Web 服务器、WebFlux、编解码与 Jackson 的自动配置，不连接数据库与 Redis；
     * {@code JsonMapper} 用平台的 {@link JsonMappers#strict()}。
     */
    @SpringBootConfiguration
    @ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        CodecsAutoConfiguration.class,
        HttpHandlerAutoConfiguration.class,
        WebFluxAutoConfiguration.class,
        NettyReactiveWebServerAutoConfiguration.class
    })
    static class BaselineApplication {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMappers.strict();
        }

        @Bean
        AgentInstanceCache agentInstanceCache() {
            // 每次模型调用取走一步：先调用 echo 工具，再根据工具结果作答
            ScriptedModel model = ScriptedModel.builder()
                    .respond(r ->
                            r.toolCall("call_1", "echo", Map.of("text", "苹果")).usage(30, 5))
                    .respondTo((request, r) -> r.text("工具返回了："
                                    + request.toolResults().getFirst().getOutput().stream()
                                            .map(block -> ((TextBlock) block).getText())
                                            .reduce("", String::concat))
                            .usage(60, 8))
                    .build();
            TestAssembly assembly = TestAssembly.builder(model)
                    .tools(StaticToolProvider.always(RecordingTool.echo("echo")))
                    .build();
            return new AgentInstanceCache(assembly.assembler());
        }

        @Bean
        RouterFunction<ServerResponse> baselineRoutes(AgentInstanceCache cache) {
            ResolvedAgentSpec spec = TestSpecs.agent().agentVersionId("agv_p1").build();
            return RouterFunctions.route()
                    .GET("/p1/ping", request -> ServerResponse.ok().bodyValue(Map.of("status", "ok")))
                    .POST(
                            "/p1/agent-runs",
                            request -> request.bodyToMono(RunRequest.class)
                                    .flatMap(body -> runTurn(cache, spec, body))
                                    .flatMap(result -> ServerResponse.ok().bodyValue(result)))
                    .build();
        }

        private static Mono<RunResult> runTurn(AgentInstanceCache cache, ResolvedAgentSpec spec, RunRequest body) {
            // 装配可能较慢，不放在事件循环线程上
            return Mono.fromCallable(() -> cache.acquire(spec, TestSpecs.noneEnvironment()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(handle -> streamOnce(handle, body).doFinally(signal -> cache.release(handle)));
        }

        private static Mono<RunResult> streamOnce(AgentHandle handle, RunRequest body) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId(body.threadId())
                    .userId(USER)
                    .build();
            return handle.agent()
                    .streamEvents(List.of(new UserMessage(body.prompt())), ctx)
                    .collectList()
                    .map(events -> new RunResult(
                            events.stream()
                                    .filter(AgentResultEvent.class::isInstance)
                                    .map(e -> ((AgentResultEvent) e).getResult().getTextContent())
                                    .reduce((first, second) -> second)
                                    .orElse(""),
                            events.stream()
                                    .filter(ToolCallStartEvent.class::isInstance)
                                    .map(e -> ((ToolCallStartEvent) e).getToolCallName())
                                    .toList(),
                            events.stream()
                                    .map(AgentEvent::getType)
                                    .map(Enum::name)
                                    .toList(),
                            Clock.systemUTC().instant()));
        }
    }
}
