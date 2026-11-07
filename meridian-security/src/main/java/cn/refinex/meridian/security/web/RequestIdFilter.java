package cn.refinex.meridian.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MVC 服务的请求 ID 过滤器：读取或生成 {@code X-Request-Id}，写入请求属性、日志 MDC 与响应头。
 *
 * <p>排在最前面，后续过滤器（认证等）与错误响应都能拿到请求 ID。响应头在调用链之前写入，
 * 所以错误响应也带它。MVC 运行在虚拟线程上，一个请求在一个线程中处理完，MDC 在 {@code finally} 中清除。
 */
public final class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.resolve(request.getHeader(RequestIds.HEADER));
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        MDC.put(RequestIds.MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }

    /** 最高优先级：在认证等所有过滤器之前执行。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
