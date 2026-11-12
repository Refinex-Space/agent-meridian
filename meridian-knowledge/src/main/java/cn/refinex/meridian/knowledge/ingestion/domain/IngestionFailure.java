package cn.refinex.meridian.knowledge.ingestion.domain;

/**
 * 写入失败（ADR-0051）：带错误码与是否可重试。可重试的（依赖暂时不可用、429、5xx）按 {@link RetrySchedule} 退避重试，其余直接结束作业。
 * 说明会写进 {@code kb.ingestion_jobs.error_message} 并经公开 API 返回，不能含凭据与文档内容。
 */
public final class IngestionFailure extends RuntimeException {

    /** 原文件无法解析（格式损坏、加密等）。 */
    public static final String PARSE_FAILED = "document.parse_failed";

    /** 需要 doc-parser，但没有部署。 */
    public static final String PARSER_UNAVAILABLE = "document.parser_unavailable";

    /** 解析后没有任何文字。 */
    public static final String NO_TEXT = "document.no_text";

    /** 返回的向量维度与配置不符。 */
    public static final String DIMENSION_MISMATCH = "document.embedding_dimension_mismatch";

    /** 依赖（对象存储、doc-parser、runtime）暂时不可用。 */
    public static final String UNAVAILABLE = "service.unavailable";

    /** 执行中的节点失去租约（崩溃或长时间停顿），由其他节点收回。 */
    public static final String LEASE_EXPIRED = "ingestion.lease_expired";

    private final String code;
    private final boolean retryable;

    /**
     * 创建失败。
     *
     * @param code 错误码，{@code <领域>.<原因>}
     * @param message 说明
     * @param retryable 是否可重试
     * @param cause 原因；可为空
     */
    public IngestionFailure(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /**
     * 不可重试的失败。
     *
     * @param code 错误码
     * @param message 说明
     * @return 失败
     */
    public static IngestionFailure permanent(String code, String message) {
        return new IngestionFailure(code, message, false, null);
    }

    /**
     * 依赖暂时不可用，可重试。
     *
     * @param message 说明
     * @param cause 原因；可为空
     * @return 失败
     */
    public static IngestionFailure unavailable(String message, Throwable cause) {
        return new IngestionFailure(UNAVAILABLE, message, true, cause);
    }

    /**
     * 错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 是否可重试。
     *
     * @return 可重试时为 true
     */
    public boolean retryable() {
        return retryable;
    }
}
