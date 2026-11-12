package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.Locale;

/** 切片的种类，即 {@code kb.chunks.kind}。 */
public enum ChunkKind {
    /** 正文：段落与列表项。 */
    TEXT,
    /** 表格（Markdown）。 */
    TABLE;

    /**
     * 数据库与接口中的取值。
     *
     * @return 小写的常量名
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
