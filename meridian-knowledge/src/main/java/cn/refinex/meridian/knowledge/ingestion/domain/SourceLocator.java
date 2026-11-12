package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.List;

/**
 * 切片的来源定位（架构设计「检索流程」第 5 步，供引用标注），即 {@code kb.chunks.source_locator}。
 *
 * @param documentId 文档 ID
 * @param filename 文件名
 * @param pageStart 起始页；没有分页时为空
 * @param pageEnd 结束页；没有分页时为空
 * @param headingPath 标题路径
 */
public record SourceLocator(
        String documentId, String filename, Integer pageStart, Integer pageEnd, List<String> headingPath) {

    /** 复制标题路径。 */
    public SourceLocator {
        headingPath = List.copyOf(headingPath);
    }
}
