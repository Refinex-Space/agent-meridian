package cn.refinex.meridian.knowledge.knowledgebase.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 知识库与文档的错误码（ADR-0051），码值只增不改。 */
public enum KnowledgeErrorCode implements ErrorCode {
    /** 知识库不存在，或不属于当前项目。 */
    KNOWLEDGE_BASE_NOT_FOUND("knowledge_base.not_found", 404, "知识库不存在"),
    /** 文档不存在，或不在该知识库中。 */
    DOCUMENT_NOT_FOUND("document.not_found", 404, "文档不存在"),
    /** 不支持的文档类型。 */
    UNSUPPORTED_TYPE("document.unsupported_type", 400, "不支持的文档类型"),
    /** 文档超过大小上限。 */
    TOO_LARGE("document.too_large", 413, "文档超过大小上限"),
    /** 文档的写入正在排队或进行，不能重新处理。 */
    PROCESSING("document.processing", 409, "文档正在写入"),
    /** 检索时知识库的 Embedding 或 rerank 模型不存在、种类不符（T19）。 */
    MODEL_UNAVAILABLE("knowledge_base.model_unavailable", 409, "知识库的模型不可用");

    private final String code;
    private final int status;
    private final String title;

    KnowledgeErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
