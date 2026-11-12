package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.Locale;

/** 写入作业的状态，即 {@code kb.ingestion_jobs.status}。 */
public enum JobStatus {
    /** 等待认领（含等待重试）。 */
    QUEUED,
    /** 执行中，持有租约。 */
    RUNNING,
    /** 成功，切片已替换。 */
    SUCCEEDED,
    /** 最终失败：不可重试的错误，或重试用尽。 */
    FAILED;

    /**
     * 数据库中的取值。
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
    public static JobStatus of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
