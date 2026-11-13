package cn.refinex.meridian.knowledge.config;

import cn.refinex.meridian.knowledge.knowledgebase.app.KnowledgeBaseService;
import cn.refinex.meridian.knowledge.search.api.KnowledgeMcpTool;
import cn.refinex.meridian.knowledge.search.app.KnowledgeSearchService;
import cn.refinex.meridian.knowledge.web.McpApiKeyFilter;
import cn.refinex.meridian.security.apikey.ApiKeyAuthenticator;
import cn.refinex.meridian.security.apikey.ApiKeyFilter;
import cn.refinex.meridian.security.apikey.ApiKeyRateLimiter;
import cn.refinex.meridian.security.apikey.ConsoleTokenAuthenticator;
import cn.refinex.meridian.security.apikey.PublicApiAdmission;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * MCP Server {@code /mcp/knowledge}（架构设计「对外形态」，T19，P8）：MCP Java SDK 0.17.2 的
 * {@link HttpServletStreamableServerTransportProvider} 注册为 Servlet（异步），{@code McpServer.sync} 挂上工具 {@code knowledge_search}；
 * 认证在 Servlet 之前的 {@link McpApiKeyFilter}，它的结果经传输上下文交给工具（每个请求各取一次）。
 */
@Configuration(proxyBeanMethods = false)
public class McpServerConfiguration {

    /** MCP 端点，与 Servlet 的映射相同。 */
    public static final String ENDPOINT = "/mcp/knowledge";

    /** 服务信息中的名字。 */
    static final String SERVER_NAME = "meridian-knowledge";

    /** 服务信息中的版本：MCP Server 的工具与结果格式的版本，不是应用版本。 */
    static final String SERVER_VERSION = "1.0.0";

    /**
     * Streamable HTTP 传输：每个请求取出过滤器写入的主体与访问标签，放进传输上下文。
     *
     * @return 传输
     */
    @Bean
    public HttpServletStreamableServerTransportProvider knowledgeMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(request -> {
                    Map<String, Object> metadata = new HashMap<>();
                    Object principal = request.getAttribute(ApiKeyFilter.PRINCIPAL_ATTRIBUTE);
                    if (principal != null) {
                        metadata.put(KnowledgeMcpTool.PRINCIPAL_KEY, principal);
                    }
                    Object tags = request.getAttribute(McpApiKeyFilter.ACL_TAGS_ATTRIBUTE);
                    metadata.put(KnowledgeMcpTool.ACL_TAGS_KEY, tags == null ? List.of() : tags);
                    return McpTransportContext.create(metadata);
                })
                .build();
    }

    /**
     * MCP Server：只有工具能力，工具 {@code knowledge_search}。容器关闭时关闭。
     *
     * @param transport 传输
     * @param search 检索用例
     * @param bases 知识库用例
     * @return 服务
     */
    @Bean(destroyMethod = "close")
    public McpSyncServer knowledgeMcpServer(
            HttpServletStreamableServerTransportProvider transport,
            KnowledgeSearchService search,
            KnowledgeBaseService bases) {
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(new KnowledgeMcpTool(search, bases).specification(McpJsonMapper.getDefault()))
                .build();
    }

    /**
     * 把传输注册为 Servlet；Streamable HTTP 的 GET 是长连接，要求异步。
     *
     * @param transport 传输
     * @return Servlet 注册
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> knowledgeMcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, ENDPOINT);
        registration.setAsyncSupported(true);
        registration.setName("knowledge-mcp");
        return registration;
    }

    /**
     * MCP 端点的认证过滤器，与公开 API 共用认证器与限流器。
     *
     * @param authenticator API Key 认证器
     * @param consoleAuthenticator 控制台用户的认证；没有配置 OIDC 时不存在，只接受 API Key
     * @param rateLimiter 限流器；没有 Redis 时不存在，不限流
     * @param errors Spring MVC 的异常解析器
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<McpApiKeyFilter> knowledgeMcpAuthentication(
            ApiKeyAuthenticator authenticator,
            ObjectProvider<ConsoleTokenAuthenticator> consoleAuthenticator,
            ObjectProvider<ApiKeyRateLimiter> rateLimiter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        McpApiKeyFilter filter = new McpApiKeyFilter(
                new PublicApiAdmission(
                        authenticator, consoleAuthenticator.getIfAvailable(), rateLimiter.getIfAvailable()),
                errors);
        FilterRegistrationBean<McpApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setUrlPatterns(List.of("/mcp/*"));
        registration.setOrder(filter.getOrder());
        registration.setAsyncSupported(true);
        return registration;
    }
}
