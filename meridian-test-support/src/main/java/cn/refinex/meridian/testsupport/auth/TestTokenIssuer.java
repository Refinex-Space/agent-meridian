package cn.refinex.meridian.testsupport.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的令牌签发方（ADR-0044）：JDK {@link HttpServer} 上的 JWKS（{@code /oauth2/jwks}）、OIDC 发现文档与服务令牌端点
 * （{@code /oauth2/token}，客户端凭据模式），按需签发服务 JWT 或任意声明的令牌。不依赖平台模块；与 control-plane 的签发方一样用
 * RS256 与 {@code kid}，校验端按真实的 HTTP JWKS 工作（缓存、未知 {@code kid} 刷新）。
 *
 * <p>用法：{@code TestTokenIssuer.start()} 后把 {@link #jwksUrl()} 配给被测服务，用 {@link #serviceToken} 调内部接口；{@link #rotate()}
 * 换签名密钥（旧公钥仍在 JWKS 中）。线程安全。
 */
public final class TestTokenIssuer implements AutoCloseable {

    /** 服务 JWT 的默认签发方，与 control-plane 相同。 */
    public static final String SERVICE_ISSUER = "meridian-control-plane";

    private final HttpServer server;
    private final Map<String, String> clientSecrets = new ConcurrentHashMap<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private volatile List<RSAKey> keys;

    private TestTokenIssuer(HttpServer server) {
        this.server = server;
        this.keys = List.of(newKey());
    }

    /**
     * 在回环地址的随机端口启动。
     *
     * @return 签发方
     */
    public static TestTokenIssuer start() {
        return start(InetAddress.getLoopbackAddress(), 0);
    }

    /**
     * 在指定地址与端口启动（kind 中的出网代理要经宿主地址访问它）。
     *
     * @param address 监听地址
     * @param port 端口；0 表示随机
     * @return 签发方
     */
    public static TestTokenIssuer start(InetAddress address, int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(address, port), 0);
            TestTokenIssuer issuer = new TestTokenIssuer(server);
            server.createContext("/oauth2/jwks", issuer::jwks);
            server.createContext("/oauth2/token", issuer::token);
            server.createContext("/.well-known/openid-configuration", issuer::discovery);
            // 每个请求一个虚拟线程：挂在同一服务上的替身接口会回头读本服务的 JWKS，单线程执行器会自己等自己
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return issuer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 基础 URL，例如 {@code http://127.0.0.1:54321}；同时是 OIDC 用法下的签发方（{@code iss}）。
     *
     * @return URL
     */
    public String url() {
        InetAddress bound = server.getAddress().getAddress();
        // 监听全部地址时，本机经回环地址访问
        String host = bound.isAnyLocalAddress() ? "127.0.0.1" : bound.getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + server.getAddress().getPort();
    }

    /**
     * JWKS 地址。
     *
     * @return URL
     */
    public String jwksUrl() {
        return url() + "/oauth2/jwks";
    }

    /**
     * 登记一个服务客户端，令牌端点据此认证。
     *
     * @param service 服务名
     * @param secret 客户端密钥
     * @return 本签发方
     */
    public TestTokenIssuer client(String service, String secret) {
        clientSecrets.put(service, secret);
        return this;
    }

    /**
     * 在同一个 HTTP 服务上挂其他路径，模拟 control-plane 的内部接口（令牌端点与 JWKS 已在其上）。
     *
     * @param path 路径前缀
     * @param handler 处理器
     * @return 本签发方
     */
    public TestTokenIssuer route(String path, HttpHandler handler) {
        server.createContext(path, handler);
        return this;
    }

    /**
     * 签发服务 JWT（有效 5 分钟）。
     *
     * @param service 调用方服务名（{@code sub}）
     * @param audience 目标服务名（{@code aud}）
     * @return 令牌
     */
    public String serviceToken(String service, String audience) {
        Instant now = Instant.now();
        return sign(serviceClaims(service, audience, now, now.plus(Duration.ofMinutes(5)))
                .build());
    }

    /**
     * 服务 JWT 的声明构造器，测试按需改动（过期、错误的签发方）后用 {@link #sign} 签名。
     *
     * @param service 调用方服务名
     * @param audience 目标服务名
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @return 声明构造器
     */
    public static JWTClaimsSet.Builder serviceClaims(
            String service, String audience, Instant issuedAt, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .issuer(SERVICE_ISSUER)
                .subject(service)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString());
    }

    /**
     * 用当前签名密钥签名任意声明。
     *
     * @param claims 声明
     * @return 令牌
     */
    public String sign(JWTClaimsSet claims) {
        return sign(claims, keys.getFirst());
    }

    /**
     * 用一把不在 JWKS 中的密钥签名（同一个 {@code kid}），模拟伪造的令牌。
     *
     * @param claims 声明
     * @return 令牌
     */
    public String signWithForeignKey(JWTClaimsSet claims) {
        try {
            RSAKey foreign =
                    new RSAKeyGenerator(2048).keyID(keys.getFirst().getKeyID()).generate();
            return sign(claims, foreign);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 生成新的签名密钥；上一把的公钥仍在 JWKS 中。 */
    public synchronized void rotate() {
        keys = List.of(newKey(), keys.getFirst());
    }

    /**
     * 令牌端点收到的请求数。
     *
     * @return 次数
     */
    public int tokenRequests() {
        return tokenRequests.get();
    }

    /**
     * JWKS 端点收到的请求数。
     *
     * @return 次数
     */
    public int jwksRequests() {
        return jwksRequests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("k_" + UUID.randomUUID())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private void jwks(HttpExchange exchange) throws IOException {
        jwksRequests.incrementAndGet();
        List<com.nimbusds.jose.jwk.JWK> published = new ArrayList<>();
        keys.forEach(key -> published.add(key.toPublicJWK()));
        respond(exchange, 200, new JWKSet(published).toString(true));
    }

    private void discovery(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"issuer\":\"" + url() + "\",\"jwks_uri\":\"" + jwksUrl() + "\"}");
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String client = null;
        if (authorization != null && authorization.startsWith("Basic ")) {
            String pair = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            String id = URLDecoder.decode(pair.substring(0, colon), StandardCharsets.UTF_8);
            String secret = URLDecoder.decode(pair.substring(colon + 1), StandardCharsets.UTF_8);
            if (secret.equals(clientSecrets.get(id))) {
                client = id;
            }
        }
        if (client == null) {
            respond(exchange, 401, "{\"code\":\"auth.invalid_service_client\"}");
            return;
        }
        if (!"client_credentials".equals(form.get("grant_type")) || form.get("audience") == null) {
            respond(exchange, 400, "{\"code\":\"request.invalid\"}");
            return;
        }
        String token = serviceToken(client, form.get("audience"));
        respond(exchange, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
