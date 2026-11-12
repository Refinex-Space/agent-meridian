package cn.refinex.meridian.knowledge.document.domain;

import java.util.Locale;

/** 文档状态，即 {@code kb.documents.status}，与公开 API 的 {@code DocumentStatus} 一一对应。 */
public enum DocumentStatus {
    /** 等待写入（含等待重试）。 */
    QUEUED,
    /** 写入中。 */
    PROCESSING,
    /** 切片可检索。 */
    READY,
    /** 最近一次写入失败；之前写入过的切片仍可检索。 */
    FAILED;

    /**
     * 数据库与接口中的取值。
     *
     * @return 小写的常量名
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 按取值解析。
     *
     * @param value 取值
     * @return 状态
     */
    public static DocumentStatus of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
