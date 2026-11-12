package cn.refinex.meridian.knowledge.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.text.TextTokens;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Chunker}：节、标题路径、目标长度与重叠、表格整体与按行拆分、页码（ADR-0051）。 */
class ChunkerTest {

    private static final Chunker DEFAULT = new Chunker(new Chunker.Settings(512, 64, 2048));

    private static String sentence(int index) {
        return "第" + index + "条规定员工应当在出差结束后十个工作日内提交报销申请并附上全部票据。";
    }

    @Test
    void headingsOpenSectionsAndPathsFollowTheLevels() {
        ParsedDocument document = new ParsedDocument(
                List.of(
                        new Block.Paragraph("前言没有标题。", null),
                        new Block.Heading(1, "第一章 总则", null),
                        new Block.Paragraph("第一条 总则正文。", null),
                        new Block.Heading(2, "第一节 适用范围", null),
                        new Block.ListItem("正式员工", null),
                        new Block.ListItem("试用期员工", null),
                        new Block.Heading(1, "第二章 审批", null),
                        new Block.Paragraph("第二条 审批正文。", null)),
                "test");

        List<ChunkDraft> chunks = DEFAULT.split(document);

        assertThat(chunks)
                .extracting(ChunkDraft::headingPath)
                .containsExactly(List.of(), List.of("第一章 总则"), List.of("第一章 总则", "第一节 适用范围"), List.of("第二章 审批"));
        assertThat(chunks).extracting(ChunkDraft::seq).containsExactly(0, 1, 2, 3);
        assertThat(chunks.get(2).content()).isEqualTo("- 正式员工\n- 试用期员工");
        assertThat(chunks.get(1).searchText()).isEqualTo("第一章 总则\n第一条 总则正文。");
        assertThat(chunks.get(0).searchText()).isEqualTo("前言没有标题。");
        assertThat(chunks).allMatch(chunk -> chunk.kind() == ChunkKind.TEXT);
    }

    @Test
    void longSectionsAreSplitWithinTheTargetAndOverlapBySentences() {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Heading(1, "第三章 费用标准", 3));
        for (int i = 1; i <= 40; i++) {
            blocks.add(new Block.Paragraph(sentence(i) + sentence(i + 100), 3 + i / 20));
        }

        List<ChunkDraft> chunks = DEFAULT.split(new ParsedDocument(blocks, "test"));

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokens()).isLessThanOrEqualTo(512);
            assertThat(chunk.tokens()).isEqualTo(TextTokens.approximate(chunk.content()));
            assertThat(chunk.headingPath()).containsExactly("第三章 费用标准");
        });
        // 除最后一片外都接近目标长度：装箱不会过早结束
        assertThat(chunks.subList(0, chunks.size() - 1))
                .allSatisfy(chunk -> assertThat(chunk.tokens()).isGreaterThan(400));
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).content();
            String current = chunks.get(i).content();
            String firstSentence = current.substring(0, current.indexOf('。') + 1);
            assertThat(previous).as("第 %d 片以上一片末尾的句子开头", i).contains(firstSentence);
            assertThat(TextTokens.approximate(firstSentence)).isLessThanOrEqualTo(64);
        }
        assertThat(chunks.getFirst().pageStart()).isEqualTo(3);
        assertThat(chunks.getLast().pageEnd()).isEqualTo(5);
    }

    @Test
    void tablesAreWholeChunksAndOversizedTablesRepeatTheHeader() {
        StringBuilder small = new StringBuilder("| 职级 | 一线城市 |\n| --- | --- |\n| 高管 | 800 |");
        StringBuilder large = new StringBuilder("| 序号 | 说明 |\n| --- | --- |");
        for (int i = 0; i < 200; i++) {
            large.append("\n| ").append(i).append(" | 设备编号与配置需要在资产系统中逐项核对确认 |");
        }
        ParsedDocument document = new ParsedDocument(
                List.of(
                        new Block.Heading(1, "住宿", 1),
                        new Block.Paragraph("见下表。", 1),
                        new Block.Table(small.toString(), 1),
                        new Block.Paragraph("表后的说明。", 2),
                        new Block.Table(large.toString(), 2)),
                "test");

        List<ChunkDraft> chunks = DEFAULT.split(document);

        assertThat(chunks.get(0).content()).isEqualTo("见下表。");
        assertThat(chunks.get(1).kind()).isEqualTo(ChunkKind.TABLE);
        assertThat(chunks.get(1).content()).isEqualTo(small.toString());
        assertThat(chunks.get(1).headingPath()).containsExactly("住宿");
        assertThat(chunks.get(2).content()).isEqualTo("表后的说明。");
        List<ChunkDraft> pieces = chunks.subList(3, chunks.size());
        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(piece -> {
            assertThat(piece.kind()).isEqualTo(ChunkKind.TABLE);
            assertThat(piece.content()).startsWith("| 序号 | 说明 |\n| --- | --- |\n| ");
            assertThat(piece.tokens()).isLessThanOrEqualTo(2048);
            assertThat(piece.pageStart()).isEqualTo(2);
        });
        long rows = pieces.stream()
                .mapToLong(piece -> piece.content().lines().count() - 2)
                .sum();
        assertThat(rows).isEqualTo(200);
    }

    @Test
    void sentencesLongerThanTheTargetAreCutAndTheTailOverlaps() {
        Chunker small = new Chunker(new Chunker.Settings(50, 10, 100));
        String longSentence = "没有标点的长句".repeat(30);

        List<ChunkDraft> chunks =
                small.split(new ParsedDocument(List.of(new Block.Paragraph(longSentence, null)), "t"));

        assertThat(chunks)
                .hasSizeGreaterThan(3)
                .allSatisfy(chunk -> assertThat(chunk.tokens()).isLessThanOrEqualTo(50));
        assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList()))
                .contains("没有标点的长句没有标点的长句");
    }

    @Test
    void englishSentencesAreJoinedWithSpaces() {
        List<ChunkDraft> chunks = DEFAULT.split(new ParsedDocument(
                List.of(new Block.Paragraph("First rule applies. Second rule follows!", null)), "t"));

        assertThat(chunks)
                .singleElement()
                .extracting(ChunkDraft::content)
                .isEqualTo("First rule applies. Second rule follows!");
    }

    @Test
    void emptyDocumentsAndBlankBlocksYieldNothing() {
        assertThat(DEFAULT.split(new ParsedDocument(List.of(), "t"))).isEmpty();
        assertThat(DEFAULT.split(new ParsedDocument(
                        List.of(
                                new Block.Heading(1, " ", null),
                                new Block.Paragraph("  ", null),
                                new Block.ListItem(" ", null),
                                new Block.Table("  ", null)),
                        "t")))
                .isEmpty();
    }

    @Test
    void settingsAreValidated() {
        assertThatThrownBy(() -> new Chunker.Settings(0, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker.Settings(100, 100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker.Settings(100, 10, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker.Settings(100, -1, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
