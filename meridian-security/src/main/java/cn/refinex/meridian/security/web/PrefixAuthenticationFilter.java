package cn.refinex.meridian.security.web;

import cn.refinex.meridian.common.error.MeridianException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * MVC 服务按路径前缀认证的过滤器基类：第一段等于 {@code segment} 的请求必须带 {@code Authorization}，认证后把主体写入请求属性，
 * 其他路径不经过它。前缀按 {@link RequestPaths#firstSegment(String, String)} 判断，与路由匹配看到的路径一致（ADR-0041）。
 *
 * <p>过滤器在 {@code DispatcherServlet} 之前执行，抛出的异常不经过 {@code @RestControllerAdvice}：认证失败交给 Spring 的
 * {@link HandlerExceptionResolver}，由同一个 {@code MvcProblemHandler} 输出 problem+json，两种入口的错误格式一致。
 *
 * @param <P> 主体类型
 */
public abstract class PrefixAuthenticationFilter<P> extends OncePerRequestFilter implements Ordered {

    /** 在请求 ID 过滤器之后执行，认证失败的响应也带请求 ID；与 WebFlux 的认证过滤器位置相同。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final String segment;
    private final String attribute;
    private final HandlerExceptionResolver errors;

    /**
     * 创建过滤器。
     *
     * @param segment 需要认证的路径第一段，例如 {@code admin}
     * @param attribute 主体写入的请求属性名
     * @param errors Spring MVC 的异常解析器（bean 名 {@code handlerExceptionResolver}）
     */
    protected PrefixAuthenticationFilter(String segment, String attribute, HandlerExceptionResolver errors) {
        this.segment = segment;
        this.attribute = attribute;
        this.errors = errors;
    }

    /**
     * 认证一个请求。
     *
     * @param authorization {@code Authorization} 请求头的值，可为空
     * @return 主体
     * @throws MeridianException 认证失败时（{@code auth.*}）
     */
    protected abstract P authenticate(String authorization);

    @Override
    protected final void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!segment.equals(RequestPaths.firstSegment(request.getRequestURI(), request.getContextPath()))) {
            chain.doFilter(request, response);
            return;
        }
        P principal;
        try {
            principal = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (MeridianException e) {
            errors.resolveException(request, response, null, e);
            return;
        }
        request.setAttribute(attribute, principal);
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
