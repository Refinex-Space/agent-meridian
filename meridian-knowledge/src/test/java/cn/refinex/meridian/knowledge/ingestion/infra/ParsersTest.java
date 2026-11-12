package cn.refinex.meridian.knowledge.ingestion.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.knowledge.ingestion.domain.Block;
import cn.refinex.meridian.knowledge.ingestion.domain.ChunkDraft;
import cn.refinex.meridian.knowledge.ingestion.domain.ChunkKind;
import cn.refinex.meridian.knowledge.ingestion.domain.Chunker;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import cn.refinex.meridian.knowledge.ingestion.domain.PdfInspection;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 解析器与切分在真实语料上的结果（T18-A02 中不需要 doc-parser 的部分）：Word 制度文件的标题层级、条款编号与表格，纯文字 PDF 的
 * 标题与页码，PDF 的路由判断。语料见 {@code src/test/resources/corpus/README.md}。
 */
class ParsersTest {

    private static final Path CORPUS = Path.of("src/test/resources/corpus");
    private static final Chunker CHUNKER = new Chunker(new Chunker.Settings(512, 64, 2048));

    private final TikaOfficeParser office = new TikaOfficeParser();
    private final PdfBoxParser pdf = new PdfBoxParser();

    @Test
    void wordHeadingsTablesAndClauseNumbersSurvive() {
        ParsedDocument parsed = office.parse(CORPUS.resolve("travel-policy.docx"), "travel-policy.docx");

        assertThat(parsed.parser()).isEqualTo("tika");
        assertThat(parsed.blocks())
                .contains(new Block.Heading(1, "第三章 费用标准", null), new Block.Heading(2, "第二节 住宿费", null));
        assertThat(parsed.blocks())
                .filteredOn(Block.Paragraph.class::isInstance)
                .extracting(block -> ((Block.Paragraph) block).text())
                .anyMatch(text -> text.startsWith("第十一条 出差人员的住宿费实行限额标准"));
        assertThat(parsed.blocks())
                .filteredOn(Block.Table.class::isInstance)
                .singleElement()
                .extracting(block -> ((Block.Table) block).markdown())
                .isEqualTo("""
                        | 职级 | 一线城市（元/晚） | 省会城市（元/晚） | 其他城市（元/晚） |
                        | --- | --- | --- | --- |
                        | 高级管理人员 | 800 | 600 | 450 |
                        | 中层管理人员 | 600 | 450 | 350 |
                        | 其他人员 | 500 | 380 | 300 |""");
    }

    @Test
    void wordChunksCarryHeadingPathsTablesStayWholeAndLengthsStayWithinTheTarget() {
        List<ChunkDraft> chunks =
                CHUNKER.split(office.parse(CORPUS.resolve("travel-policy.docx"), "travel-policy.docx"));

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.headingPath()).isNotEmpty();
            assertThat(chunk.pageStart()).as("Word 没有页码").isNull();
        });
        assertThat(chunks)
                .filteredOn(chunk -> chunk.kind() == ChunkKind.TEXT)
                .allSatisfy(chunk -> assertThat(chunk.tokens()).isBetween(1, 512));
        ChunkDraft table = chunks.stream()
                .filter(chunk -> chunk.kind() == ChunkKind.TABLE)
                .findFirst()
                .orElseThrow();
        assertThat(table.headingPath()).containsExactly("第三章 费用标准", "第二节 住宿费");
        assertThat(table.content()).contains("| 高级管理人员 | 800 | 600 | 450 |").contains("| 其他人员 | 500 | 380 | 300 |");
        List<ChunkDraft> traffic = chunks.stream()
                .filter(chunk -> chunk.headingPath().equals(List.of("第三章 费用标准", "第一节 交通费")))
                .toList();
        assertThat(traffic).as("交通费一节超过 512 token，切成多片").hasSizeGreaterThan(1);
        assertThat(traffic.getFirst().content()).startsWith("第七条 ");
        assertThat(traffic.getLast().content()).contains("第十条 ");
        assertThat(chunks.getFirst().headingPath()).containsExactly("第一章 总则");
    }

    @Test
    void plainPdfHeadingsComeFromFontSizesAndChunksKeepPages() {
        Path file = CORPUS.resolve("reimbursement-guide.pdf");
        assertThat(pdf.inspect(file).needsLayoutParser()).isFalse();

        ParsedDocument parsed = pdf.parse(file);
        List<ChunkDraft> chunks = CHUNKER.split(parsed);

        assertThat(parsed.parser()).isEqualTo("pdfbox");
        assertThat(parsed.blocks())
                .contains(
                        new Block.Heading(1, "费用报销操作指引", 1),
                        new Block.Heading(3, "（一）提交申请", 1),
                        new Block.Heading(2, "三、注意事项", 2));
        assertThat(chunks)
                .extracting(ChunkDraft::headingPath)
                .contains(
                        List.of("费用报销操作指引", "一、报销范围"),
                        List.of("费用报销操作指引", "二、报销流程", "（二）审批与付款"),
                        List.of("费用报销操作指引", "三、注意事项"));
        ChunkDraft notes = chunks.stream()
                .filter(chunk -> chunk.headingPath().getLast().equals("三、注意事项"))
                .findFirst()
                .orElseThrow();
        assertThat(notes.pageStart()).isEqualTo(2);
        assertThat(notes.pageEnd()).isEqualTo(2);
        assertThat(notes.content())
                .startsWith("同一张发票只能报销一次")
                .contains("\n对报销流程有疑问的")
                .doesNotContain("财务\n部");
        assertThat(chunks.getFirst().pageStart()).isEqualTo(1);
    }

    @Test
    void scannedAndRuledPdfsAreRoutedToTheLayoutParser() {
        PdfInspection table = pdf.inspect(CORPUS.resolve("equipment-rules.pdf"));
        PdfInspection scanned = pdf.inspect(CORPUS.resolve("scanned-notice.pdf"));

        assertThat(table.hasTables()).isTrue();
        assertThat(table.scanned()).isFalse();
        assertThat(table.pages()).isEqualTo(2);
        assertThat(scanned.scanned()).isTrue();
        assertThat(scanned.textChars()).isZero();
    }

    @Test
    void brokenFilesAreNotRetryable(@TempDir Path dir) throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.pdf"), "%PDF-1.7 不是 PDF");

        assertThatThrownBy(() -> pdf.inspect(broken)).isInstanceOfSatisfying(IngestionFailure.class, e -> {
            assertThat(e.code()).isEqualTo(IngestionFailure.PARSE_FAILED);
            assertThat(e.retryable()).isFalse();
        });
    }

    @Test
    void textFallsBackToGb18030WhenItIsNotUtf8(@TempDir Path dir) throws IOException {
        Path gbk = dir.resolve("gbk.txt");
        Files.write(gbk, "差旅住宿标准".getBytes(Charset.forName("GBK")));
        Path utf8 = Files.writeString(dir.resolve("utf8.md"), "# 标题");

        assertThat(office.readText(gbk)).isEqualTo("差旅住宿标准");
        assertThat(office.readText(utf8)).isEqualTo("# 标题");
    }

    @Test
    void docParserBlocksMapToTheIntermediateRepresentation() {
        List<Block> blocks = DocParserClient.toBlocks(List.of(
                new DocParserClient.WireBlock("heading", "第一章", null, 1, 1),
                new DocParserClient.WireBlock("paragraph", "正文", null, 0, 1),
                new DocParserClient.WireBlock("list_item", "条目", null, 0, 2),
                new DocParserClient.WireBlock("table", "表", "| a |\n| --- |", 0, 2),
                new DocParserClient.WireBlock("caption", "未知类型", null, 0, null)));

        assertThat(blocks)
                .containsExactly(
                        new Block.Heading(1, "第一章", 1),
                        new Block.Paragraph("正文", 1),
                        new Block.ListItem("条目", 2),
                        new Block.Table("| a |\n| --- |", 2),
                        new Block.Paragraph("未知类型", null));
        assertThat(new DocParserClient(null, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1))
                        .available())
                .isFalse();
    }
}
