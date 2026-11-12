package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.Locale;

/** 写入的阶段，即 {@code kb.ingestion_jobs.stage}。 */
public enum IngestionStage {
    /** 解析：下载原文件，转成中间表示。 */
    PARSING,
    /** 切分。 */
    CHUNKING,
    /** 计算向量（经 runtime）。 */
    EMBEDDING,
    /** 入库：一个事务内替换文档的切片。 */
    STORING;

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
     * @param value 取值；可为空
     * @return 阶段；输入为空时为空
     */
    public static IngestionStage of(String value) {
        return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
