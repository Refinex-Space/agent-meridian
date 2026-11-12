package cn.refinex.meridian.knowledge.web;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.knowledge.config.KnowledgeProperties;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeErrorCode;
import cn.refinex.meridian.security.web.MvcProblemHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上传超过 multipart 上限时给出规范写明的 413 {@code document.too_large}（框架默认映射为通用的 {@code request.payload_too_large}）。
 * multipart 的上限按 {@code meridian.knowledge.documents.max-size} 设置；输出仍经 {@link MvcProblemHandler}，格式与其他错误相同。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadLimitAdvice {

    private final MvcProblemHandler problems;
    private final DataSize maxSize;

    /**
     * 创建处理器。
     *
     * @param problems problem+json 的输出
     * @param properties knowledge 配置，取文档的大小上限
     */
    public UploadLimitAdvice(MvcProblemHandler problems, KnowledgeProperties properties) {
        this.problems = problems;
        this.maxSize = properties.documents().maxSize();
    }

    /**
     * 转换上传超限。
     *
     * @param error 框架的异常
     * @param request 当前请求
     * @return 413 的 problem+json
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> tooLarge(MaxUploadSizeExceededException error, HttpServletRequest request) {
        return problems.handle(
                new MeridianException(KnowledgeErrorCode.TOO_LARGE, "文档超过大小上限 " + maxSize.toMegabytes() + " MB"),
                request);
    }
}
