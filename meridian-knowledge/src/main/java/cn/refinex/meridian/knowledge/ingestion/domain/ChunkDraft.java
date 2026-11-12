package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.List;

/**
 * 切分得到的一个切片，还没有向量。
 *
 * @param seq 在文档中的顺序，从 0 开始
 * @param kind 种类
 * @param content 正文（表格为 Markdown），不含标题路径
 * @param headingPath 标题路径，从最高层级到最近的标题；文档开头没有标题的部分为空列表
 * @param pageStart 起始页；没有分页时为空
 * @param pageEnd 结束页；没有分页时为空
 * @param tokens 正文的 token 估算（{@code TextTokens.approximate}）
 */
public record ChunkDraft(
        int seq,
        ChunkKind kind,
        String content,
        List<String> headingPath,
        Integer pageStart,
        Integer pageEnd,
        int tokens) {

    /** 复制标题路径。 */
    public ChunkDraft {
        headingPath = List.copyOf(headingPath);
    }

    /**
     * Embedding 的输入与全文索引的文本：标题路径（以 {@code " > "} 连接）换行后接正文，让只出现在标题中的词也能被检索到。
     *
     * @return 文本
     */
    public String searchText() {
        return headingPath.isEmpty() ? content : String.join(" > ", headingPath) + "\n" + content;
    }
}
