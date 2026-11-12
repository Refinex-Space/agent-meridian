package cn.refinex.meridian.knowledge.document.app;

import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionProgress;

/**
 * 文档与它最近一次写入的进度。
 *
 * @param document 文档
 * @param progress 最近一次写入作业的进度；没有作业时为空
 */
public record DocumentDetails(Document document, IngestionProgress progress) {}
