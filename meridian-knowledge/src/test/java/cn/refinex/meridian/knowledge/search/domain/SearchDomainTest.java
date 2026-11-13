package cn.refinex.meridian.knowledge.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** RRF 融合与检索方式。 */
class SearchDomainTest {

    private static Candidate chunk(String id) {
        return new Candidate(id, "kb_a", "doc_a", "a.md", "text", "正文 " + id, List.of("标题", id), null, null);
    }

    @Test
    void candidatesRankedHighInBothListsWinAndScoresFollowTheFormula() {
        List<ScoredCandidate> fused = new ReciprocalRankFusion(60)
                .fuse(List.of(
                        List.of(chunk("a"), chunk("b"), chunk("c")), List.of(chunk("b"), chunk("d"), chunk("a"))));

        assertThat(fused).extracting(s -> s.candidate().chunkId()).containsExactly("b", "a", "d", "c");
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(fused.get(1).score()).isEqualTo(1.0 / 61 + 1.0 / 63);
        assertThat(fused.get(2).score()).isEqualTo(1.0 / 62);
        assertThat(fused.get(3).score()).isEqualTo(1.0 / 63);
    }

    @Test
    void tiesKeepFirstSeenOrderAndDuplicatesInOneListCountOnce() {
        List<ScoredCandidate> fused = new ReciprocalRankFusion(60)
                .fuse(List.of(List.of(chunk("a"), chunk("a"), chunk("b")), List.of(chunk("c"))));

        assertThat(fused).extracting(s -> s.candidate().chunkId()).containsExactly("a", "c", "b");
        assertThat(fused.get(0).score()).isEqualTo(fused.get(1).score());
        assertThat(fused.get(2).score()).isEqualTo(1.0 / 62);
    }

    @Test
    void emptyRankingsFuseToNothingAndKMustBePositive() {
        assertThat(new ReciprocalRankFusion(ReciprocalRankFusion.DEFAULT_K).fuse(List.of(List.of(), List.of())))
                .isEmpty();
        assertThatThrownBy(() -> new ReciprocalRankFusion(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rerankTextIsHeadingPathPlusContentLikeTheEmbeddingInput() {
        assertThat(chunk("x").rerankText()).isEqualTo("标题 / x\n正文 x");
        assertThat(new Candidate("chk_1", "kb", "doc", "f", "text", "正文", List.of(), 1, 2).rerankText())
                .isEqualTo("正文");
    }

    @Test
    void plansNameTheEvaluatedConfigurations() {
        assertThat(SearchPlan.VECTOR_ONLY.label()).isEqualTo("vector");
        assertThat(SearchPlan.FULL_TEXT_ONLY.label()).isEqualTo("full_text");
        assertThat(SearchPlan.HYBRID.label()).isEqualTo("hybrid");
        assertThat(SearchPlan.STANDARD.label()).isEqualTo("hybrid+rerank");
        assertThatThrownBy(() -> new SearchPlan(false, false, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
