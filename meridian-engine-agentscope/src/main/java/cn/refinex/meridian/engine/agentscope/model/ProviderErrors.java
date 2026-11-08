package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.common.error.MeridianException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 供应商错误的分类，见架构设计「模型调用」中的错误处理：连接失败、5xx、429 是可重试的供应商错误，其余 4xx 重试也不会成功。
 *
 * <p>平台异常（预算、限流、租约）与中断不是供应商错误，原样传播，不重试、不切换。
 */
final class ProviderErrors {

    private ProviderErrors() {}

    /** 错误的类别。 */
    enum Kind {
        /** 不是供应商错误：平台异常、中断等，原样传播。 */
        NOT_PROVIDER,
        /** 可重试的供应商错误：连接失败、超时、5xx、429。 */
        RETRYABLE,
        /** 不可重试的供应商错误：认证失败、参数错误等 4xx，以及无法解析的响应。 */
        REJECTED
    }

    /**
     * 分类一个错误。
     *
     * @param error 底层客户端的错误
     * @return 类别
     */
    static Kind classify(Throwable error) {
        if (isPlatformOrInterrupt(error)) {
            return Kind.NOT_PROVIDER;
        }
        Integer status = statusOf(error);
        if (status != null) {
            return status == 429 || status >= 500 ? Kind.RETRYABLE : Kind.REJECTED;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpTransportException || t instanceof IOException || t instanceof TimeoutException) {
                return Kind.RETRYABLE;
            }
        }
        return Kind.REJECTED;
    }

    /**
     * 错误链上第一个 HTTP 状态码。
     *
     * @param error 错误
     * @return 状态码；没有时为空
     */
    static Integer statusOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ModelHttpException http && http.getStatusCode() != null) {
                return http.getStatusCode();
            }
            if (t instanceof HttpTransportException transport && transport.getStatusCode() != null) {
                return transport.getStatusCode();
            }
        }
        return null;
    }

    private static boolean isPlatformOrInterrupt(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof MeridianException || t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
