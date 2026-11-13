package cn.refinex.meridian.testsupport.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link ScriptedOpenAiServer} 的协议：脚本选择、流式格式、错误与断流、Embedding 与 rerank（T09b）。 */
class ScriptedOpenAiServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static ScriptedOpenAiServer server;

    @BeforeAll
    static void start() {
        server = ScriptedOpenAiServer.builder().script("""
                        name: retry-after
                        steps:
                          - error: {status: 429, retry_after: 3}
                        """).start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void allBuiltinScriptsLoad() {
        assertThat(server.scriptNames()).containsAll(ScriptLoader.BUILTIN);
    }

    @Test
    void messagesWithoutAMarkerAreEchoed() throws Exception {
        JsonNode response = json(post("/v1/chat/completions", """
                {"model": "scripted", "messages": [{"role": "user", "content": "你好"}]}
                """));

        assertThat(response.path("object").asString()).isEqualTo("chat.completion");
        JsonNode choice = response.path("choices").get(0);
        assertThat(choice.path("message").path("content").asString()).isEqualTo("echo: 你好");
        assertThat(choice.path("finish_reason").asString()).isEqualTo("stop");
        assertThat(response.path("usage").path("total_tokens").asInt()).isPositive();
    }

    @Test
    void theStepIsTheNumberOfAssistantMessagesAfterTheMarker() throws Exception {
        JsonNode first = json(post("/v1/chat/completions", """
                {"model": "scripted", "messages": [
                  {"role": "user", "content": "#script:tool-once 查订单"}]}
                """));
        JsonNode call =
                first.path("choices").get(0).path("message").path("tool_calls").get(0);
        assertThat(call.path("function").path("name").asString()).isEqualTo("lookup_order");
        assertThat(JSON.readTree(call.path("function").path("arguments").asString())
                        .path("order_id")
                        .asString())
                .isEqualTo("o-1001");
        assertThat(first.path("choices").get(0).path("finish_reason").asString())
                .isEqualTo("tool_calls");

        String id = call.path("id").asString();
        JsonNode second = json(post("/v1/chat/completions", """
                {"model": "scripted", "messages": [
                  {"role": "user", "content": "#script:tool-once 查订单"},
                  {"role": "assistant", "content": null, "tool_calls": [
                    {"id": "%s", "type": "function", "function": {"name": "lookup_order", "arguments": "{}"}}]},
                  {"role": "tool", "tool_call_id": "%s", "content": "已发货"}]}
                """.formatted(id, id)));
        assertThat(second.path("choices").get(0).path("message").path("content").asString())
                .isEqualTo("订单 o-1001 已发货，预计明天送达。");

        JsonNode again = json(post("/v1/chat/completions", """
                {"model": "scripted", "messages": [{"role": "user", "content": "#script:tool-once 查订单"}]}
                """));
        assertThat(again.path("choices")
                        .get(0)
                        .path("message")
                        .path("tool_calls")
                        .get(0)
                        .path("id")
                        .asString())
                .as("同一请求得到同一工具调用 ID")
                .isEqualTo(id);
    }

    @Test
    void streamingFollowsTheOpenAiChunkFormat() throws Exception {
        List<String> data = sse(post("/v1/chat/completions", """
                {"model": "scripted", "stream": true, "stream_options": {"include_usage": true},
                 "messages": [{"role": "user", "content": "#script:tool-once"}]}
                """));

        assertThat(data.getLast()).isEqualTo("[DONE]");
        List<JsonNode> chunks =
                data.subList(0, data.size() - 1).stream().map(JSON::readTree).toList();
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(chunk.path("object").asString()).isEqualTo("chat.completion.chunk"));
        assertThat(chunks.getFirst()
                        .path("choices")
                        .get(0)
                        .path("delta")
                        .path("role")
                        .asString())
                .isEqualTo("assistant");
        JsonNode usage = chunks.getLast();
        assertThat(usage.path("choices")).isEmpty();
        assertThat(usage.path("usage").path("prompt_tokens").asInt()).isPositive();
        JsonNode finish = chunks.get(chunks.size() - 2).path("choices").get(0);
        assertThat(finish.path("finish_reason").asString()).isEqualTo("tool_calls");
        StringBuilder arguments = new StringBuilder();
        String name = null;
        for (JsonNode chunk : chunks) {
            for (JsonNode call : chunk.path("choices").path(0).path("delta").path("tool_calls")) {
                assertThat(call.path("index").asInt()).isZero();
                if (call.path("function").has("name")) {
                    name = call.path("function").path("name").asString();
                }
                arguments.append(call.path("function").path("arguments").asString(""));
            }
        }
        assertThat(name).isEqualTo("lookup_order");
        assertThat(JSON.readTree(arguments.toString()).path("order_id").asString())
                .isEqualTo("o-1001");
    }

    @Test
    void errorStepsReturnTheStatusBeforeAnyChunk() throws Exception {
        HttpResponse<String> unavailable = post("/v1/chat/completions", """
                {"model": "scripted", "stream": true, "messages": [{"role": "user", "content": "#script:error-before-first-chunk"}]}
                """);
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(json(unavailable).path("error").path("message").asString()).isNotBlank();

        HttpResponse<String> limited = post("/v1/chat/completions", """
                {"model": "scripted", "messages": [{"role": "user", "content": "#script:retry-after"}]}
                """);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("3");
    }

    @Test
    void failAfterChunksBreaksTheConnectionMidStream() {
        assertThatThrownBy(() -> post("/v1/chat/completions", """
                        {"model": "scripted", "stream": true,
                         "messages": [{"role": "user", "content": "#script:error-after-first-chunk"}]}
                        """)).isInstanceOf(IOException.class);
    }

    @Test
    void unknownScriptsAreRejected() throws Exception {
        HttpResponse<String> response = post("/v1/chat/completions", """
                {"model": "scripted", "messages": [{"role": "user", "content": "#script:nope"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("message").asString()).contains("nope");
    }

    @Test
    void scriptsWrittenToTheDirectoryWhileRunningAreLoadedOnFirstUse(@TempDir Path directory) throws Exception {
        try (ScriptedOpenAiServer withDirectory =
                ScriptedOpenAiServer.builder().scriptsDirectory(directory).start()) {
            Files.writeString(directory.resolve("later.yaml"), """
                    name: later
                    steps:
                      - text: 后来写进目录的脚本
                    """);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + withDirectory.port() + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model": "scripted", "messages": [{"role": "user", "content": "#script:later 开始"}]}
                            """))
                    .build();

            JsonNode response = json(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));

            assertThat(response.path("choices")
                            .get(0)
                            .path("message")
                            .path("content")
                            .asString())
                    .isEqualTo("后来写进目录的脚本");
            assertThat(withDirectory.scriptNames()).contains("later");
        }
    }

    @Test
    void embeddingsAreDeterministicUnitVectors() throws Exception {
        JsonNode first = json(post("/v1/embeddings", """
                {"model": "scripted-embedding", "input": ["差旅标准", "报销票据"]}
                """));
        JsonNode second = json(post("/v1/embeddings", """
                {"model": "scripted-embedding", "input": "差旅标准", "encoding_format": "base64", "dimensions": 1024}
                """));

        JsonNode vector = first.path("data").get(0).path("embedding");
        assertThat(vector.size()).isEqualTo(ScriptedVectors.DEFAULT_DIMENSIONS);
        double norm = 0;
        for (JsonNode value : vector) {
            norm += value.asDouble() * value.asDouble();
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(first.path("data").get(1).path("embedding")).isNotEqualTo(vector);
        ByteBuffer decoded = ByteBuffer.wrap(Base64.getDecoder()
                        .decode(second.path("data").get(0).path("embedding").asString()))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(decoded.getFloat()).isEqualTo(vector.get(0).floatValue());
        assertThat(first.path("usage").path("prompt_tokens").asInt()).isEqualTo(8);
    }

    @Test
    void rerankOrdersByKeywordOverlap() throws Exception {
        JsonNode response = json(post("/compatible-api/v1/reranks", """
                {"model": "scripted-rerank", "query": "住宿标准", "top_n": 2,
                 "documents": ["报销需要发票", "差旅住宿标准为每晚 500 元", "住宿需要提前预订"]}
                """));

        JsonNode results = response.path("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("index").asInt()).isEqualTo(1);
        assertThat(results.get(0).path("relevance_score").asDouble()).isEqualTo(1.0);
        assertThat(results.get(1).path("index").asInt()).isEqualTo(2);
        assertThat(response.path("usage").path("total_tokens").asInt()).isPositive();
    }

    @Test
    void callsAreRecordedWithScriptAndStep() throws Exception {
        server.clearCalls();
        post("/v1/chat/completions", """
                {"model": "scripted", "messages": [{"role": "user", "content": "#script:chat"}]}
                """);

        assertThat(server.calls()).anySatisfy(call -> {
            assertThat(call.path()).isEqualTo("/v1/chat/completions");
            assertThat(call.script()).isEqualTo("chat");
            assertThat(call.step()).isZero();
            assertThat(call.stream()).isFalse();
            assertThat(call.body()).as("记录请求体原文").contains("\"content\": \"#script:chat\"");
        });
    }

    private static HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static List<String> sse(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("text/event-stream");
        List<String> data = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            if (line.startsWith("data: ")) {
                data.add(line.substring("data: ".length()));
            }
        }
        return data;
    }
}
