package cn.refinex.meridian.knowledge.search.api;

import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchHit;
import cn.refinex.meridian.api.internal.model.InternalKnowledgeSearchResult;
import cn.refinex.meridian.api.model.KnowledgeSearchHit;
import cn.refinex.meridian.api.model.KnowledgeSearchResult;
import cn.refinex.meridian.api.model.KnowledgeSource;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.knowledge.search.app.SearchOutcome;
import cn.refinex.meridian.knowledge.search.domain.Candidate;
import cn.refinex.meridian.knowledge.search.domain.ScoredCandidate;
import java.util.List;

/** 检索结果到公开 API 与内部接口 DTO 的映射，以及三个入口共用的入参检查。 */
public final class SearchDtos {

    private SearchDtos() {}

    /**
     * 查询去掉首尾空白后不能为空（规范的 {@code minLength} 管不到全是空白的查询）。
     *
     * @param query 查询
     * @return 原查询
     * @throws ValidationException 查询全是空白时（字段 {@code query}）
     */
    public static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED, "查询不能为空", List.of(new FieldViolation("query", "查询不能全是空白")));
        }
        return query;
    }

    /**
     * 公开 API 的结果。
     *
     * @param outcome 检索结果
     * @return DTO
     */
    public static KnowledgeSearchResult toPublic(SearchOutcome outcome) {
        return new KnowledgeSearchResult(
                outcome.hits().stream().map(SearchDtos::toPublic).toList());
    }

    /**
     * 内部接口的结果。
     *
     * @param outcome 检索结果
     * @return DTO
     */
    public static InternalKnowledgeSearchResult toInternal(SearchOutcome outcome) {
        return new InternalKnowledgeSearchResult(
                outcome.hits().stream().map(SearchDtos::toInternal).toList(), outcome.query());
    }

    private static KnowledgeSearchHit toPublic(ScoredCandidate hit) {
        Candidate c = hit.candidate();
        return new KnowledgeSearchHit(
                c.chunkId(),
                c.knowledgeBaseId(),
                c.documentId(),
                c.filename(),
                KnowledgeSearchHit.KindEnum.fromValue(c.kind()),
                c.content(),
                hit.score(),
                new KnowledgeSource(c.pageStart(), c.pageEnd(), c.headingPath()));
    }

    private static InternalKnowledgeSearchHit toInternal(ScoredCandidate hit) {
        Candidate c = hit.candidate();
        return new InternalKnowledgeSearchHit(
                c.chunkId(),
                c.knowledgeBaseId(),
                c.documentId(),
                c.filename(),
                c.kind(),
                c.content(),
                hit.score(),
                c.pageStart(),
                c.pageEnd(),
                c.headingPath());
    }
}
