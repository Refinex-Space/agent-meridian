package cn.refinex.meridian.controlplane.support;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * knowledge 内部接口 {@code GET /internal/v1/knowledge-bases/{id}} 的替身（ADR-0051）：{@link #register} 登记的知识库返回它的租户与项目，
 * 其余 404；{@link #down} 为真时返回 503。记下每次请求的 {@code Authorization}，测试据此校验 control-plane 的出站服务 JWT。
 */
public final class StubKnowledge {

    private final HttpServer server;
    private final Map<String, String[]> bases = new ConcurrentHashMap<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile boolean down;

    private StubKnowledge(HttpServer server) {
        this.server = server;
    }

    /**
     * 启动替身。
     *
     * @return 替身
     */
    public static StubKnowledge start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubKnowledge stub = new StubKnowledge(server);
            server.createContext("/internal/v1/knowledge-bases/", stub::lookup);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 基础 URL。
     *
     * @return URL
     */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 登记一个属于某个项目的知识库。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @return 知识库 ID
     */
    public String register(String tenantId, String projectId) {
        String id = Ids.newId(IdPrefix.KNOWLEDGE_BASE);
        bases.put(id, new String[] {tenantId, projectId});
        return id;
    }

    /**
     * 模拟 knowledge 不可用。
     *
     * @param unavailable 是否返回 503
     */
    public void down(boolean unavailable) {
        this.down = unavailable;
    }

    /**
     * 收到的 {@code Authorization} 请求头。
     *
     * @return 请求头的值
     */
    public List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    private void lookup(HttpExchange exchange) throws IOException {
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        String id = exchange.getRequestURI().getPath().substring("/internal/v1/knowledge-bases/".length());
        String[] owner = bases.get(id);
        if (down) {
            respond(exchange, 503, problem("service.unavailable", 503));
        } else if (owner == null) {
            respond(exchange, 404, problem("knowledge_base.not_found", 404));
        } else {
            respond(exchange, 200, """
                    {"id":"%s","tenant_id":"%s","project_id":"%s","name":"制度","embedding_model":"mdl_e"}
                    """.formatted(id, owner[0], owner[1]));
        }
    }

    private static String problem(String code, int status) {
        return "{\"type\":\"urn:meridian:error:" + code + "\",\"title\":\"x\",\"status\":" + status
                + ",\"detail\":null,\"instance\":\"/\",\"code\":\"" + code + "\",\"request_id\":\"r\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
