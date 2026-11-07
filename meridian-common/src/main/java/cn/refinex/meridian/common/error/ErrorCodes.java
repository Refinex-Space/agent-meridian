package cn.refinex.meridian.common.error;

import java.util.HashSet;
import java.util.Set;

/**
 * 错误码的注册校验，供各领域错误码枚举的单元测试调用，见 {@link ErrorCode} 的注册约定。
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    /**
     * 校验一组错误码：码值格式正确、HTTP 状态在 4xx 与 5xx 之间、标题非空、码值互不重复。
     *
     * @param codes 一个领域的全部错误码，通常是 {@code XxxErrorCode.values()}
     * @throws IllegalArgumentException 任一条件不满足时，消息中列出违规的码值
     */
    public static void verify(ErrorCode... codes) {
        Set<String> seen = new HashSet<>();
        for (ErrorCode code : codes) {
            if (code.code() == null
                    || !ErrorCode.CODE_PATTERN.matcher(code.code()).matches()) {
                throw new IllegalArgumentException("错误码格式应为 <领域>.<原因>（小写 snake_case）：" + code.code());
            }
            if (code.status() < 400 || code.status() > 599) {
                throw new IllegalArgumentException("错误码的 HTTP 状态应在 400 至 599 之间：" + code.code());
            }
            if (code.title() == null || code.title().isBlank()) {
                throw new IllegalArgumentException("错误码缺少默认标题：" + code.code());
            }
            if (!seen.add(code.code())) {
                throw new IllegalArgumentException("错误码重复：" + code.code());
            }
        }
    }
}
