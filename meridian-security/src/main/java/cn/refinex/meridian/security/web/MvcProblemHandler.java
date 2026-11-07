package cn.refinex.meridian.security.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * MVC 服务的 problem+json 适配器：捕获处理器抛出的所有异常（包括找不到处理器的 404），交给 {@link ProblemMapper}。
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public final class MvcProblemHandler {

    private final ProblemMapper mapper;

    /**
     * 创建适配器。
     *
     * @param mapper 共用的映射逻辑
     */
    public MvcProblemHandler(ProblemMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把异常写成 problem+json。
     *
     * @param error 处理器抛出的异常
     * @param request 当前请求
     * @return problem+json 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(Exception error, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIds.ATTRIBUTE);
        String requestId = attribute instanceof String id ? id : RequestIds.resolve(null);
        Problem problem = mapper.map(error, requestId, request.getRequestURI());
        return ResponseEntity.status(problem.status())
                .header(RequestIds.HEADER, requestId)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem.body());
    }
}
