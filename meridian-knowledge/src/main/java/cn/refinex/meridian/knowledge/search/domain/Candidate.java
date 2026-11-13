package cn.refinex.meridian.knowledge.search.domain;

import java.util.List;
import java.util.Objects;

/**
 * 一路召回得到的切片（架构设计「检索流程」第 2、5 步），带引用标注需要的来源定位。
 *
 * @param chunkId 切片 ID（{@code chk_…}）
 * @param knowledgeBaseId 知识库 ID
 * @param documentId 文档 ID
 * @param filename 文档的文件名
 * @param kind {@code text} 或 {@code table}
 * @param content 正文，不含标题路径
 * @param headingPath 标题路径
 * @param pageStart 起始页；没有分页时为空
 * @param pageEnd 结束页；没有分页时为空
 */
public record Candidate(
        String chunkId,
        String knowledgeBaseId,
        String documentId,
        String filename,
        String kind,
        String content,
        List<String> headingPath,
        Integer pageStart,
        Integer pageEnd) {

    /**
     * 校验并复制标题路径。
     *
     * @throws NullPointerException 缺少切片 ID 或正文时
     */
    public Candidate {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(content, "content");
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }

    /**
     * 交给 rerank 的文本："标题路径 + 正文"，与写入时 Embedding 的输入相同（架构设计「写入流程」第 4 步）。
     *
     * @return 文本
     */
    public String rerankText() {
        return headingPath.isEmpty() ? content : String.join(" / ", headingPath) + "\n" + content;
    }
}
