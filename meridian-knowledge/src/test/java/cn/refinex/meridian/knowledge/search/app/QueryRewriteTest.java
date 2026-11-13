package cn.refinex.meridian.knowledge.search.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 改写输出的清理：取第一个非空行、去掉成对的引号，空或过长时视为改写失败。 */
class QueryRewriteTest {

    @Test
    void takesTheFirstNonBlankLineWithoutQuotes() {
        assertThat(KnowledgeSearchService.cleanRewrite("\n  \"上海 出差 住宿 标准\"  \n解释：……"))
                .isEqualTo("上海 出差 住宿 标准");
        assertThat(KnowledgeSearchService.cleanRewrite("「年终奖 发放 月数」")).isEqualTo("年终奖 发放 月数");
        assertThat(KnowledgeSearchService.cleanRewrite("password rotation period"))
                .isEqualTo("password rotation period");
    }

    @Test
    void blankOrOverlongOutputMeansNoRewrite() {
        assertThat(KnowledgeSearchService.cleanRewrite(null)).isNull();
        assertThat(KnowledgeSearchService.cleanRewrite("  \n \"\" ")).isNull();
        assertThat(KnowledgeSearchService.cleanRewrite("字".repeat(KnowledgeSearchService.MAX_QUERY_LENGTH + 1)))
                .isNull();
    }
}
