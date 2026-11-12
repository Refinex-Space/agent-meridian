package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
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
 * MVC 服务（control-plane、knowledge）的公开 API 认证过滤器，与 WebFlux 的 {@link ApiKeyWebFilter} 规则相同（D55）：{@code /v1/}
 * 下的每个请求都要认证（API Key，或控制台用户的 OIDC 令牌加 {@code Meridian-Project}），认证后限流，主体写入请求属性
 * {@link #PRINCIPAL_ATTRIBUTE}，{@code PlatformContext} 以 {@code ScopedValue} 绑定在后续的过滤器与处理器上。
 *
 * <p>过滤器在 {@code DispatcherServlet} 之前执行，认证失败与超限交给 Spring 的 {@link HandlerExceptionResolver}，由
 * {@code MvcProblemHandler} 输出 problem+json。其他路径（管理 API、内部接口、健康检查）不经过它。
 */
public final class ApiKeyFilter extends OncePerRequestFilter implements Ordered {

    /** 请求属性的键：认证后的 {@link PublicApiPrincipal}，作用域检查从这里读取。 */
    public static final String PRINCIPAL_ATTRIBUTE = ApiKeyFilter.class.getName() + ".principal";

    private final PublicApiAdmission admission;
    private final HandlerExceptionResolver errors;

    /**
     * 创建过滤器。
     *
     * @param admission 认证与限流
     * @param errors Spring MVC 的异常解析器（bean 名 {@code handlerExceptionResolver}）
     */
    public ApiKeyFilter(PublicApiAdmission admission, HandlerExceptionResolver errors) {
        this.admission = admission;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PublicApiAdmission.API_SEGMENT.equals(
                RequestPaths.firstSegment(request.getRequestURI(), request.getContextPath()))) {
            chain.doFilter(request, response);
            return;
        }
        PublicApiAdmission.Admission admitted;
        try {
            admitted = admission.admit(
                    request.getHeader(HttpHeaders.AUTHORIZATION), request.getHeader(PublicApiAdmission.PROJECT_HEADER));
        } catch (MeridianException e) {
            errors.resolveException(request, response, null, e);
            return;
        }
        Optional<MeridianException> limited = admitted.applyTo(response::setHeader);
        if (limited.isPresent()) {
            errors.resolveException(request, response, null, limited.get());
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, admitted.principal());
        try {
            PlatformContexts.<Void, Exception>call(admitted.principal().toContext(), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    public int getOrder() {
        return ApiKeyWebFilter.ORDER;
    }
}
