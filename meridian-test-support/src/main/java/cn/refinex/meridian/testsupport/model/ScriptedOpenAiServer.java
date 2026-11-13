package cn.refinex.meridian.testsupport.model;

import cn.refinex.meridian.testsupport.model.ModelScript.Step;
import cn.refinex.meridian.testsupport.model.ModelScript.ToolCall;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 脚本化模型服务：OpenAI 兼容的本地 HTTP 服务，runtime 把它当成普通的 OpenAI 兼容供应商调用，测试因此不依赖真实模型
 * （架构原则第 10 条）。可以在测试中内嵌启动（{@link #builder()}），也可以独立启动（{@link #main}，{@code scripts/dev/scripted-model.sh}）。
 *
 * <p>协议：
 * <ul>
 *   <li>{@code POST /v1/chat/completions}：流式（SSE，{@code data: [DONE]} 结束，{@code stream_options.include_usage} 时多一个用量块）
 *       与非流式。脚本选择：请求中最近一条以 {@code #script:<名称>} 开头的用户消息选中该脚本，没有时用默认的回显；当前是第几步由
 *       该消息之后的 assistant 消息数决定，服务本身不保存会话状态。
 *   <li>{@code POST /v1/embeddings}：按文本哈希生成确定的向量（{@link ScriptedVectors}），支持 {@code dimensions} 与
 *       {@code encoding_format=base64}。
 *   <li>{@code POST /compatible-api/v1/reranks}：DashScope 兼容格式的 rerank，按关键词重叠打分。
 *   <li>{@code GET /v1/models}：OpenAI 兼容的模型清单（{@code scripted}、{@code scripted-embedding}），供应商账号的连通性测试列它
 *       （T23）。
 * </ul>
 *
 * <p>不读取任何密钥（忽略 {@code Authorization}），不向外网发请求，只监听回环地址（独立启动时可用 {@code --host} 改）。为了断言重试
 * 次数等调用情况，服务记录收到的每次请求（{@link #calls()}），记录不影响响应。
 *
 * <p>线程安全：请求在虚拟线程上并发处理，脚本表创建后不变。
 */
public final class ScriptedOpenAiServer implements AutoCloseable {

    /** 独立启动的默认端口，与 {@code dev-config/providers/scripted.yaml}、{@code scripts/dev/scripted-model.sh} 一致。 */
    public static final int DEFAULT_PORT = 18090;

    /** 选择脚本的用户消息前缀。 */
    public static final String SCRIPT_MARKER = "#script:";

    /** 没有脚本标记时的回显前缀。 */
    public static final String ECHO_PREFIX = "echo: ";

    /** 回显的最大字符数。 */
    static final int ECHO_MAX_CHARS = 200;

    /** 断流时声明的响应长度：远大于实际写出的字节，提前关闭让客户端读到不完整的响应。 */
    private static final long TRUNCATED_LENGTH = 1L << 30;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger log = LoggerFactory.getLogger(ScriptedOpenAiServer.class);

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, ModelScript> scripts;
    private final List<Path> directories;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    private ScriptedOpenAiServer(String host, int port, Map<String, ModelScript> scripts, List<Path> directories) {
        this.scripts = new ConcurrentHashMap<>(scripts);
        this.directories = List.copyOf(directories);
        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("脚本化模型服务无法监听 " + host + ":" + port, e);
        }
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * 遇到不认识的脚本时重新读一遍脚本目录：运行中的服务可以加载后来写进目录的脚本（R2 的验收预演按本次运行生成的知识库 ID
     * 写脚本）。
     *
     * @param name 脚本名
     * @return 脚本；目录中仍然没有时为空
     */
    private synchronized ModelScript reload(String name) {
        for (Path directory : directories) {
            scripts.putAll(ScriptLoader.directory(directory));
        }
        return scripts.get(name);
    }

    /**
     * 新建构建器：默认监听回环地址的随机端口，加载全部内置脚本。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 独立启动：{@code --port=18090}（默认）、{@code --host=127.0.0.1}（默认）、{@code --scripts=<目录>}（额外的脚本，同名覆盖内置）。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        Builder builder = builder().port(DEFAULT_PORT);
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                builder.port(Integer.parseInt(arg.substring("--port=".length())));
            } else if (arg.startsWith("--host=")) {
                builder.host(arg.substring("--host=".length()));
            } else if (arg.startsWith("--scripts=")) {
                builder.scriptsDirectory(Path.of(arg.substring("--scripts=".length())));
            } else {
                throw new IllegalArgumentException("未知参数：" + arg + "（可用：--port=、--host=、--scripts=）");
            }
        }
        ScriptedOpenAiServer server = builder.start();
        log.info("ScriptedOpenAiServer 已启动：{}，脚本：{}", server.baseUrl(), server.scriptNames());
    }

    /**
     * OpenAI 兼容端点，作为供应商账号的 {@code base_url}。
     *
     * @return 例如 {@code http://127.0.0.1:18090/v1}
     */
    public String baseUrl() {
        return origin() + "/v1";
    }

    /**
     * DashScope 兼容 rerank 的端点，作为 rerank 模型的 {@code base_url}。
     *
     * @return 例如 {@code http://127.0.0.1:18090/compatible-api/v1}
     */
    public String rerankBaseUrl() {
        return origin() + "/compatible-api/v1";
    }

    /**
     * 监听的端口。
     *
     * @return 端口
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * 可用的脚本名。
     *
     * @return 脚本名，按字母排序
     */
    public List<String> scriptNames() {
        return scripts.keySet().stream().sorted().toList();
    }

    /**
     * 收到的请求，按到达顺序。
     *
     * @return 请求记录
     */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** 清空请求记录。 */
    public void clearCalls() {
        calls.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private String origin() {
        String host = server.getAddress().getHostString();
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/v1/models".equals(path)) {
                models(exchange);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                error(exchange, 405, "只支持 POST 与 GET /v1/models", null);
                return;
            }
            JsonNode body;
            String raw;
            try (InputStream in = exchange.getRequestBody()) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                body = JSON.readTree(raw);
            } catch (RuntimeException e) {
                error(exchange, 400, "请求体不是 JSON", null);
                return;
            }
            switch (path) {
                case "/v1/chat/completions" -> chat(exchange, body, raw);
                case "/v1/embeddings" -> embeddings(exchange, body);
                case "/compatible-api/v1/reranks" -> rerank(exchange, body);
                default -> error(exchange, 404, "未知的路径：" + path, null);
            }
        } catch (IOException e) {
            // 客户端断开（例如中断时取消订阅），不再写响应
        }
    }

    // ---- 模型清单 ----

    /** OpenAI 兼容的模型清单：连通性测试只看状态码，内容是这个服务能回应的两个模型名。 */
    private static void models(HttpExchange exchange) throws IOException {
        ObjectNode list = JSON.createObjectNode().put("object", "list");
        ArrayNode data = list.putArray("data");
        for (String id : List.of("scripted", "scripted-embedding")) {
            data.addObject().put("id", id).put("object", "model").put("owned_by", "meridian");
        }
        json(exchange, 200, list);
    }

    // ---- 对话补全 ----

    private void chat(HttpExchange exchange, JsonNode body, String raw) throws IOException {
        Selection selection = select(body.path("messages"));
        boolean stream = body.path("stream").asBoolean(false);
        calls.add(new Call("/v1/chat/completions", selection.script(), selection.step(), stream, raw));
        ModelScript script;
        if (selection.script() == null) {
            script = new ModelScript("echo", null, List.of(Step.text(echo(selection.lastUserText()))));
        } else {
            script = scripts.get(selection.script());
            if (script == null) {
                script = reload(selection.script());
            }
            if (script == null) {
                error(exchange, 400, "未知的脚本：" + selection.script() + "，可用：" + scriptNames(), null);
                return;
            }
        }
        Step step = selection.step() < script.steps().size()
                ? script.steps().get(selection.step())
                : Step.text("脚本 " + script.name() + " 已执行完毕。");
        if (step.error() != null) {
            error(
                    exchange,
                    step.error().status(),
                    step.error().message(),
                    step.error().retryAfterSeconds());
            return;
        }
        String responseId = "chatcmpl-scripted-"
                + hash(selection.script() + ":" + selection.markerIndex() + ":" + selection.step());
        List<ToolCall> toolCalls = toolCalls(step, selection);
        int[] usage = usage(step, body.path("messages"), toolCalls);
        Completion completion = new Completion(
                responseId, Instant.now().getEpochSecond(), body.path("model").asString("scripted"));
        if (stream) {
            boolean includeUsage =
                    body.path("stream_options").path("include_usage").asBoolean(false);
            streamChat(exchange, completion, step, toolCalls, usage, includeUsage);
        } else {
            completeChat(exchange, completion, step, toolCalls, usage);
        }
    }

    private void completeChat(
            HttpExchange exchange, Completion completion, Step step, List<ToolCall> toolCalls, int[] usage)
            throws IOException {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant");
        message.put("content", step.text());
        if (step.reasoning() != null) {
            message.put("reasoning_content", step.reasoning());
        }
        if (!toolCalls.isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCall call : toolCalls) {
                ObjectNode item = calls.addObject().put("id", call.id()).put("type", "function");
                item.putObject("function").put("name", call.name()).put("arguments", call.arguments());
            }
        }
        ObjectNode response = completion.base("chat.completion");
        ObjectNode choice = response.putArray("choices").addObject().put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", step.effectiveFinishReason());
        response.set("usage", usageNode(usage));
        byte[] bytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (step.failAfterChunks() != null) {
            // 非流式的断流：只写出一半响应就关闭连接
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes, 0, bytes.length / 2);
            exchange.getResponseBody().flush();
            throw new IOException("脚本要求断流");
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void streamChat(
            HttpExchange exchange,
            Completion completion,
            Step step,
            List<ToolCall> toolCalls,
            int[] usage,
            boolean includeUsage)
            throws IOException {
        List<ObjectNode> chunks = new ArrayList<>();
        ObjectNode first = JSON.createObjectNode().put("role", "assistant").put("content", "");
        chunks.add(first);
        if (step.reasoning() != null) {
            for (String piece : split(step.reasoning(), step.chunkSize())) {
                chunks.add(JSON.createObjectNode().put("reasoning_content", piece));
            }
        }
        if (step.text() != null) {
            for (String piece : split(step.text(), step.chunkSize())) {
                chunks.add(JSON.createObjectNode().put("content", piece));
            }
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall call = toolCalls.get(i);
            ObjectNode start = JSON.createObjectNode();
            ObjectNode startCall = start.putArray("tool_calls")
                    .addObject()
                    .put("index", i)
                    .put("id", call.id())
                    .put("type", "function");
            startCall.putObject("function").put("name", call.name()).put("arguments", "");
            chunks.add(start);
            for (String piece :
                    split(call.arguments(), Math.max(1, call.arguments().length() / 2))) {
                ObjectNode fragment = JSON.createObjectNode();
                fragment.putArray("tool_calls")
                        .addObject()
                        .put("index", i)
                        .putObject("function")
                        .put("arguments", piece);
                chunks.add(fragment);
            }
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        boolean truncate = step.failAfterChunks() != null;
        exchange.sendResponseHeaders(200, truncate ? TRUNCATED_LENGTH : 0);
        OutputStream out = exchange.getResponseBody();
        for (int i = 0; i < chunks.size(); i++) {
            if (truncate && i >= step.failAfterChunks()) {
                // 声明的长度没有写满就结束：客户端读到不完整的响应，即"首包之后断流"
                throw new IOException("脚本要求断流");
            }
            if (i > 0 && step.delayMillis() > 0) {
                sleep(step.delayMillis());
            }
            ObjectNode chunk = completion.base("chat.completion.chunk");
            ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
            choice.set("delta", chunks.get(i));
            choice.putNull("finish_reason");
            write(out, JSON.writeValueAsString(chunk));
        }
        ObjectNode last = completion.base("chat.completion.chunk");
        ObjectNode choice = last.putArray("choices").addObject().put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", step.effectiveFinishReason());
        write(out, JSON.writeValueAsString(last));
        if (includeUsage) {
            ObjectNode usageChunk = completion.base("chat.completion.chunk");
            usageChunk.putArray("choices");
            usageChunk.set("usage", usageNode(usage));
            write(out, JSON.writeValueAsString(usageChunk));
        }
        write(out, "[DONE]");
    }

    private static void write(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 选中的脚本与步骤：最近一条带标记的用户消息，以及它之后的 assistant 消息数。 */
    private static Selection select(JsonNode messages) {
        String lastUserText = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if (!"user".equals(message.path("role").asString(""))) {
                continue;
            }
            String text = text(message.path("content")).strip();
            if (lastUserText.isEmpty()) {
                lastUserText = text;
            }
            if (text.startsWith(SCRIPT_MARKER)) {
                String rest = text.substring(SCRIPT_MARKER.length()).strip();
                String name = rest.split("\\s+", 2)[0];
                int assistants = 0;
                for (int j = i + 1; j < messages.size(); j++) {
                    if ("assistant".equals(messages.get(j).path("role").asString(""))) {
                        assistants++;
                    }
                }
                return new Selection(name, i, assistants, lastUserText);
            }
        }
        int assistants = 0;
        for (JsonNode message : messages) {
            if ("assistant".equals(message.path("role").asString(""))) {
                assistants++;
            }
        }
        return new Selection(null, -1, assistants, lastUserText);
    }

    /** 消息内容：字符串，或 OpenAI 的内容块数组（取其中的文本）。 */
    private static String text(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asString(""))) {
                    text.append(part.path("text").asString(""));
                }
            }
        }
        return text.toString();
    }

    private static String echo(String lastUserText) {
        String text = lastUserText.length() > ECHO_MAX_CHARS ? lastUserText.substring(0, ECHO_MAX_CHARS) : lastUserText;
        return ECHO_PREFIX + text;
    }

    /** 工具调用的 ID：脚本给出的，或按脚本、选中消息的位置与步骤生成（同一请求总是同一 ID，同一会话的不同轮次不重复）。 */
    private static List<ToolCall> toolCalls(Step step, Selection selection) {
        List<ToolCall> calls = new ArrayList<>(step.toolCalls().size());
        for (int i = 0; i < step.toolCalls().size(); i++) {
            ToolCall call = step.toolCalls().get(i);
            String id = call.id() != null
                    ? call.id()
                    : "call_"
                            + hash(selection.script() + ":" + selection.markerIndex() + ":" + selection.step() + ":"
                                    + i);
            calls.add(new ToolCall(id, call.name(), call.arguments()));
        }
        return calls;
    }

    /** 用量：脚本给出的，或按字符数估算（输入为全部消息文本，输出为文本、思考与工具入参）。 */
    private static int[] usage(Step step, JsonNode messages, List<ToolCall> toolCalls) {
        if (step.usage() != null) {
            return new int[] {step.usage().promptTokens(), step.usage().completionTokens()};
        }
        int prompt = 0;
        for (JsonNode message : messages) {
            prompt += 4 + ScriptedVectors.tokens(text(message.path("content")));
        }
        int completion = ScriptedVectors.tokens(step.text()) + ScriptedVectors.tokens(step.reasoning());
        for (ToolCall call : toolCalls) {
            completion += ScriptedVectors.tokens(call.name()) + ScriptedVectors.tokens(call.arguments());
        }
        return new int[] {prompt, Math.max(1, completion)};
    }

    private static ObjectNode usageNode(int[] usage) {
        ObjectNode node = JSON.createObjectNode()
                .put("prompt_tokens", usage[0])
                .put("completion_tokens", usage[1])
                .put("total_tokens", usage[0] + usage[1]);
        node.putObject("prompt_tokens_details").put("cached_tokens", 0);
        node.putObject("completion_tokens_details").put("reasoning_tokens", 0);
        return node;
    }

    // ---- Embedding 与 rerank ----

    private void embeddings(HttpExchange exchange, JsonNode body) throws IOException {
        calls.add(new Call("/v1/embeddings", null, 0, false, null));
        JsonNode input = body.path("input");
        List<String> texts = new ArrayList<>();
        if (input.isString()) {
            texts.add(input.asString());
        } else if (input.isArray()) {
            input.forEach(item -> texts.add(item.asString("")));
        }
        if (texts.isEmpty()) {
            error(exchange, 400, "input 不能为空", null);
            return;
        }
        int dimensions = body.path("dimensions").asInt(ScriptedVectors.DEFAULT_DIMENSIONS);
        boolean base64 = "base64".equals(body.path("encoding_format").asString("float"));
        ObjectNode response = JSON.createObjectNode().put("object", "list");
        ArrayNode data = response.putArray("data");
        int tokens = 0;
        for (int i = 0; i < texts.size(); i++) {
            float[] vector = ScriptedVectors.embed(texts.get(i), dimensions);
            ObjectNode item = data.addObject().put("object", "embedding").put("index", i);
            if (base64) {
                ByteBuffer buffer =
                        ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (float value : vector) {
                    buffer.putFloat(value);
                }
                item.put("embedding", Base64.getEncoder().encodeToString(buffer.array()));
            } else {
                ArrayNode values = item.putArray("embedding");
                for (float value : vector) {
                    values.add(value);
                }
            }
            tokens += ScriptedVectors.tokens(texts.get(i));
        }
        response.put("model", body.path("model").asString("scripted-embedding"));
        response.putObject("usage").put("prompt_tokens", tokens).put("total_tokens", tokens);
        json(exchange, 200, response);
    }

    private void rerank(HttpExchange exchange, JsonNode body) throws IOException {
        calls.add(new Call("/compatible-api/v1/reranks", null, 0, false, null));
        String query = body.path("query").asString("");
        List<String> documents = new ArrayList<>();
        body.path("documents").forEach(item -> documents.add(item.asString("")));
        if (query.isEmpty() || documents.isEmpty()) {
            error(exchange, 400, "query 与 documents 不能为空", null);
            return;
        }
        List<double[]> scores = new ArrayList<>();
        int tokens = 0;
        for (int i = 0; i < documents.size(); i++) {
            scores.add(new double[] {i, ScriptedVectors.overlap(query, documents.get(i))});
            tokens += ScriptedVectors.tokens(query) + ScriptedVectors.tokens(documents.get(i));
        }
        // 得分相同按原顺序，结果确定
        scores.sort((a, b) -> b[1] != a[1] ? Double.compare(b[1], a[1]) : Double.compare(a[0], b[0]));
        int topN = body.path("top_n").asInt(documents.size());
        ObjectNode response = JSON.createObjectNode().put("object", "list");
        ArrayNode results = response.putArray("results");
        for (double[] score : scores.subList(0, Math.min(topN, scores.size()))) {
            results.addObject().put("index", (int) score[0]).put("relevance_score", score[1]);
        }
        response.put("model", body.path("model").asString("scripted-rerank"));
        response.put("id", "rerank-scripted-" + hash(query + documents));
        response.putObject("usage").put("prompt_tokens", tokens).put("total_tokens", tokens);
        json(exchange, 200, response);
    }

    // ---- 公共 ----

    private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** OpenAI 格式的错误响应。 */
    private static void error(HttpExchange exchange, int status, String message, Integer retryAfterSeconds)
            throws IOException {
        if (retryAfterSeconds != null) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
        }
        ObjectNode body = JSON.createObjectNode();
        body.putObject("error")
                .put("message", message == null ? "脚本化的错误" : message)
                .put("type", status >= 500 ? "server_error" : "invalid_request_error")
                .put("code", String.valueOf(status));
        json(exchange, status, body);
    }

    private static List<String> split(String text, int size) {
        List<String> pieces = new ArrayList<>();
        int[] codePoints = text.codePoints().toArray();
        for (int from = 0; from < codePoints.length; from += size) {
            pieces.add(new String(codePoints, from, Math.min(size, codePoints.length - from)));
        }
        return pieces;
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("流式输出被中断", e);
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }

    /**
     * 收到的一次请求。
     *
     * @param path 路径
     * @param script 选中的脚本；没有标记（回显）或不是对话补全时为空
     * @param step 步骤序号，从 0 开始
     * @param stream 是否流式
     * @param body 对话补全的请求体原文（UTF-8），比较请求前缀用（T10b）；其他路径为空
     */
    public record Call(String path, String script, int step, boolean stream, String body) {}

    /** 选中的脚本、带标记的消息位置、步骤与最后一条用户消息的文本。 */
    private record Selection(String script, int markerIndex, int step, String lastUserText) {}

    /** 一次补全的公共字段。 */
    private record Completion(String id, long created, String model) {

        ObjectNode base(String object) {
            return JSON.createObjectNode()
                    .put("id", id)
                    .put("object", object)
                    .put("created", created)
                    .put("model", model);
        }
    }

    /** {@link ScriptedOpenAiServer} 的构建器。 */
    public static final class Builder {

        private String host = "127.0.0.1";
        private int port;
        private final Map<String, ModelScript> scripts = new LinkedHashMap<>(ScriptLoader.builtin());
        private final List<Path> directories = new ArrayList<>();

        private Builder() {}

        /**
         * 监听的地址。
         *
         * @param value 地址，默认 {@code 127.0.0.1}
         * @return 本构建器
         */
        public Builder host(String value) {
            this.host = value;
            return this;
        }

        /**
         * 监听的端口。
         *
         * @param value 端口，0 表示随机（默认）
         * @return 本构建器
         */
        public Builder port(int value) {
            this.port = value;
            return this;
        }

        /**
         * 加入一个脚本，同名覆盖内置脚本。
         *
         * @param script 脚本
         * @return 本构建器
         */
        public Builder script(ModelScript script) {
            scripts.put(script.name(), script);
            return this;
        }

        /**
         * 加入一个 YAML 脚本。
         *
         * @param yaml 脚本文本
         * @return 本构建器
         */
        public Builder script(String yaml) {
            return script(ScriptLoader.parse(yaml));
        }

        /**
         * 加入目录下的全部 {@code *.yaml} 脚本；运行中遇到不认识的脚本名时会重新读这个目录。
         *
         * @param directory 目录
         * @return 本构建器
         */
        public Builder scriptsDirectory(Path directory) {
            scripts.putAll(ScriptLoader.directory(directory));
            directories.add(directory);
            return this;
        }

        /**
         * 启动服务。
         *
         * @return 已在监听的服务
         */
        public ScriptedOpenAiServer start() {
            return new ScriptedOpenAiServer(host, port, scripts, directories);
        }
    }
}
