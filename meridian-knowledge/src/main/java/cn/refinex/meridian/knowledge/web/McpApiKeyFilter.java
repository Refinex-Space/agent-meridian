package cn.refinex.meridian.knowledge.web;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.knowledge.document.domain.AclTags;
import cn.refinex.meridian.security.apikey.ApiKeyErrorCode;
import cn.refinex.meridian.security.apikey.ApiKeyFilter;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.apikey.PublicApiAdmission;
import cn.refinex.meridian.security.web.PrefixAuthenticationFilter;
import cn.refinex.meridian.security.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * MCP Server（{@code /mcp/knowledge}，T19）的认证：与公开 API 相同的准入（API Key，或控制台用户的 OIDC 令牌加
 * {@code Meridian-Project}，认证后按主体限流，ADR-0044），要求 {@code knowledge:read}。主体写入请求属性
 * {@link ApiKeyFilter#PRINCIPAL_ATTRIBUTE}，终端用户的访问标签取请求头 {@value #ACL_TAGS_HEADER}（逗号分隔，规则同上传文档的
 * {@code acl_tags}），写入 {@link #ACL_TAGS_ATTRIBUTE}。
 *
 * <p>标签放在请求头而不是工具入参：标签由接入方的 MCP 客户端配置（平台信任 API Key 所属项目声明的属性，架构设计「对外形态」），
 * 不交给调用工具的模型填写。没有这个头时只能检索到不限标签的切片。
 *
 * <p>只处理第一段为 {@code mcp} 的路径；失败交给 {@link HandlerExceptionResolver} 输出 problem+json。
 */
public final class McpApiKeyFilter extends OncePerRequestFilter implements Ordered {

    /** 终端用户访问标签的请求头。 */
    public static final String ACL_TAGS_HEADER = "Meridian-Acl-Tags";

    /** 请求属性的键：解析后的访问标签（{@code List<String>}）。 */
    public static final String ACL_TAGS_ATTRIBUTE = McpApiKeyFilter.class.getName() + ".aclTags";

    /** 需要认证的路径第一段。 */
    static final String SEGMENT = "mcp";

    private final PublicApiAdmission admission;
    private final HandlerExceptionResolver errors;

    /**
     * 创建过滤器。
     *
     * @param admission 公开 API 的认证与限流
     * @param errors Spring MVC 的异常解析器（bean 名 {@code handlerExceptionResolver}）
     */
    public McpApiKeyFilter(PublicApiAdmission admission, HandlerExceptionResolver errors) {
        this.admission = admission;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SEGMENT.equals(RequestPaths.firstSegment(request.getRequestURI(), request.getContextPath()))) {
            chain.doFilter(request, response);
            return;
        }
        try {
            PublicApiAdmission.Admission admitted = admission.admit(
                    request.getHeader(HttpHeaders.AUTHORIZATION), request.getHeader(PublicApiAdmission.PROJECT_HEADER));
            Optional<MeridianException> limited = admitted.applyTo(response::setHeader);
            if (limited.isPresent()) {
                throw limited.get();
            }
            if (!admitted.principal().hasScope(ApiKeyScope.KNOWLEDGE_READ)) {
                throw new ForbiddenException(
                        ApiKeyErrorCode.INSUFFICIENT_SCOPE, "缺少作用域 " + ApiKeyScope.KNOWLEDGE_READ.value() + "，不能检索知识库");
            }
            request.setAttribute(ApiKeyFilter.PRINCIPAL_ATTRIBUTE, admitted.principal());
            request.setAttribute(ACL_TAGS_ATTRIBUTE, AclTags.parse(request.getHeader(ACL_TAGS_HEADER)));
        } catch (MeridianException e) {
            errors.resolveException(request, response, null, e);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        // 与公开 API 的认证过滤器同一位置：请求 ID 过滤器之后
        return PrefixAuthenticationFilter.ORDER;
    }
}
