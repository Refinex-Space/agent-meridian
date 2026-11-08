package cn.refinex.meridian.runtime.tooling.domain;

import java.net.URI;
import java.util.Objects;

/**
 * 租户的联网搜索后端配置（架构设计「平台工具」：{@code web_search} 的后端按租户配置）。T15b 起由 control-plane 的
 * {@code tenant_settings} 下发，开发期取自 {@code dev-config/agents/*.yaml} 的 {@code kind: tenant_settings}。
 *
 * @param backend 后端；必填
 * @param baseUrl 后端的根地址，例如 {@code https://api.bochaai.com}、自建 SearXNG 的 {@code http://searxng:8080}；必填
 * @param secretId API 密钥的凭据 ID，经 {@code SecretResolver} 解析后只在请求头中出现；SearXNG 可为空
 */
public record WebSearchSettings(Backend backend, URI baseUrl, String secretId) {

    /**
     * 创建配置。
     *
     * @throws NullPointerException 缺少后端或根地址时
     * @throws IllegalArgumentException 根地址不是 http 或 https，或需要密钥的后端没有凭据时
     */
    public WebSearchSettings {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
            throw new IllegalArgumentException("联网搜索后端的地址必须是 http 或 https：" + baseUrl);
        }
        if (backend.requiresSecret() && (secretId == null || secretId.isBlank())) {
            throw new IllegalArgumentException("联网搜索后端 " + backend.value() + " 需要凭据 secret_id");
        }
    }

    /** 已实现的后端。博查是国内可直连的商业 API；SearXNG 是可自建的元搜索，适合内网部署（ADR-0035）。 */
    public enum Backend {
        /** 自建 SearXNG（{@code /search?format=json}），不需要密钥。 */
        SEARXNG("searxng", false),
        /** 博查 Web Search API（{@code POST /v1/web-search}），Bearer 密钥。 */
        BOCHA("bocha", true);

        private final String value;
        private final boolean requiresSecret;

        Backend(String value, boolean requiresSecret) {
            this.value = value;
            this.requiresSecret = requiresSecret;
        }

        /**
         * 配置中的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }

        /**
         * 是否需要 API 密钥。
         *
         * @return 需要时为 true
         */
        public boolean requiresSecret() {
            return requiresSecret;
        }
    }
}
