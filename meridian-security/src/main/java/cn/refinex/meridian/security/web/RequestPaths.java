package cn.refinex.meridian.security.web;

import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * 认证过滤器判断请求属于哪个接口前缀的唯一入口（WebFlux 与 MVC）。
 *
 * <p>必须与路由匹配看到的路径一致：WebFlux 的 {@code PathPattern} 按段匹配，比较的是百分号解码、去掉矩阵参数（{@code ;…}）之后的值。
 * 直接对原始路径做 {@code startsWith("/internal/")} 时，{@code /internal;a=b/v1/...} 或 {@code /%69nternal/v1/...} 判断为不在前缀内、
 * 跳过认证，却仍被路由到内部接口（R1 安全审查，ADR-0041）。
 */
public final class RequestPaths {

    private RequestPaths() {}

    /**
     * 返回应用内路径的第一个非空段，取路由匹配用的值（解码、去掉矩阵参数）。
     *
     * @param request 请求
     * @return 第一个段；路径为空或只有分隔符时为空字符串
     */
    public static String firstSegment(ServerHttpRequest request) {
        return firstSegment(request.getPath().pathWithinApplication());
    }

    /**
     * MVC 版本：按请求 URI 与上下文路径解析出应用内路径，再取第一个非空段。参数只用字符串，WebFlux 应用的类路径上没有 servlet API
     * 也能加载本类。
     *
     * @param requestUri 原始请求 URI（{@code HttpServletRequest.getRequestURI()}，未解码）
     * @param contextPath 上下文路径（{@code HttpServletRequest.getContextPath()}），可为空字符串
     * @return 第一个段；路径为空或只有分隔符时为空字符串
     */
    public static String firstSegment(String requestUri, String contextPath) {
        return firstSegment(RequestPath.parse(requestUri, contextPath).pathWithinApplication());
    }

    private static String firstSegment(PathContainer path) {
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment
                    && !segment.valueToMatch().isEmpty()) {
                return segment.valueToMatch();
            }
        }
        return "";
    }
}
