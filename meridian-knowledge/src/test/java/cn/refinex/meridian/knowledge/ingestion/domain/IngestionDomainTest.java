package cn.refinex.meridian.knowledge.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 写入的领域规则：重试计划、PDF 的路由判断、Markdown 与纯文本的结构、句子拆分、状态取值（ADR-0051）。 */
class IngestionDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void retryableFailuresBackOffExponentiallyThreeTimes() {
        RetrySchedule schedule = new RetrySchedule(4, Duration.ofSeconds(30));

        assertThat(schedule.next(1, true, NOW)).contains(NOW.plusSeconds(30));
        assertThat(schedule.next(2, true, NOW)).contains(NOW.plusSeconds(60));
        assertThat(schedule.next(3, true, NOW)).contains(NOW.plusSeconds(120));
        assertThat(schedule.next(4, true, NOW)).as("第 4 次（第 3 次重试）失败后不再重试").isEmpty();
        assertThat(schedule.next(1, false, NOW)).as("不可重试的失败直接结束").isEmpty();
        assertThat(new RetrySchedule(30, Duration.ofHours(1)).next(29, true, NOW))
                .contains(NOW.plus(RetrySchedule.MAX_DELAY));
        assertThatThrownBy(() -> new RetrySchedule(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySchedule(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pdfsWithSparseTextOrGridsGoToTheLayoutParser() {
        assertThat(new PdfInspection(2, 741, 1).needsLayoutParser()).isTrue();
        assertThat(new PdfInspection(2, 741, 1).hasTables()).isTrue();
        assertThat(new PdfInspection(1, 0, 0).scanned()).isTrue();
        assertThat(new PdfInspection(3, 120, 0).scanned()).as("每页平均 40 个字符").isTrue();
        assertThat(new PdfInspection(2, 569, 0).needsLayoutParser()).isFalse();
        assertThat(new PdfInspection(0, 0, 0).scanned()).isFalse();
    }

    @Test
    void markdownKeepsHeadingsListsTablesAndCodeFences() throws IOException {
        String markdown = Files.readString(Path.of("src/test/resources/corpus/onboarding.md"), StandardCharsets.UTF_8);

        List<Block> blocks = MarkdownStructure.markdown(markdown).blocks();

        assertThat(blocks.getFirst()).isEqualTo(new Block.Heading(1, "新员工入职指南", null));
        assertThat(blocks)
                .contains(
                        new Block.Heading(2, "一、入职前准备", null),
                        new Block.Heading(3, "1. 需要提交的材料", null),
                        new Block.ListItem("身份证原件与正反面复印件一份", null),
                        new Block.ListItem("到前台登记并领取临时门禁卡", null));
        assertThat(blocks)
                .filteredOn(Block.Table.class::isInstance)
                .singleElement()
                .extracting(block -> ((Block.Table) block).markdown())
                .asString()
                .startsWith("| 系统 | 用途 | 开通方式 |\n| --- | --- | --- |")
                .contains("| 知识库 | 公司制度与项目文档 | 入职当天自动开通 |");
        assertThat(blocks)
                .filteredOn(Block.Paragraph.class::isInstance)
                .extracting(block -> ((Block.Paragraph) block).text())
                .anyMatch(text -> text.startsWith("```text\n人力资源部邮箱") && text.endsWith("```"));
        assertThat(MarkdownStructure.markdown(markdown).parser()).isEqualTo(MarkdownStructure.MARKDOWN);
    }

    @Test
    void markdownJoinsWrappedLinesAndContinuations() {
        List<Block> blocks = MarkdownStructure.markdown(
                        "﻿第一行\r\n第二行\nEnglish words\nwrap here\n\n- 列表\n  续行\n## 标题 ##\n")
                .blocks();

        assertThat(blocks)
                .containsExactly(
                        new Block.Paragraph("第一行第二行English words wrap here", null),
                        new Block.ListItem("列表续行", null),
                        new Block.Heading(2, "标题", null));
    }

    @Test
    void plainTextSplitsParagraphsOnBlankLines() {
        ParsedDocument parsed = MarkdownStructure.plainText("# 不是标题\n第二行\n\n\n第二段");

        assertThat(parsed.parser()).isEqualTo(MarkdownStructure.TEXT);
        assertThat(parsed.blocks())
                .containsExactly(new Block.Paragraph("# 不是标题第二行", null), new Block.Paragraph("第二段", null));
    }

    @Test
    void sentencesEndAtChineseAndEnglishTerminators() {
        assertThat(Sentences.split("第一句。第二句！“引号内？”Then English. Done;尾巴"))
                .containsExactly("第一句。", "第二句！", "“引号内？”", "Then English.", "Done;", "尾巴");
        assertThat(Sentences.split("版本 1.2 已发布")).containsExactly("版本 1.2 已发布");
    }

    @Test
    void enumValuesRoundTrip() {
        for (JobStatus status : JobStatus.values()) {
            assertThat(JobStatus.of(status.value())).isEqualTo(status);
        }
        for (IngestionStage stage : IngestionStage.values()) {
            assertThat(IngestionStage.of(stage.value())).isEqualTo(stage);
        }
        assertThat(IngestionStage.of(null)).isNull();
        assertThat(ChunkKind.TABLE.value()).isEqualTo("table");
    }

    @Test
    void failuresCarryCodeAndRetryability() {
        IngestionFailure permanent = IngestionFailure.permanent(IngestionFailure.NO_TEXT, "没有文字");
        IngestionFailure unavailable = IngestionFailure.unavailable("暂时不可用", null);

        assertThat(permanent.code()).isEqualTo("document.no_text");
        assertThat(permanent.retryable()).isFalse();
        assertThat(unavailable.code()).isEqualTo(IngestionFailure.UNAVAILABLE);
        assertThat(unavailable.retryable()).isTrue();
    }

    @Test
    void progressKnowsWhenItWaitsForARetry() {
        IngestionProgress waiting = new IngestionProgress(
                "ijb_1", JobStatus.QUEUED, 1, 4, null, null, null, NOW, "service.unavailable", "x", true, NOW, null);
        IngestionProgress fresh = new IngestionProgress(
                "ijb_2", JobStatus.QUEUED, 0, 4, null, null, null, NOW, null, null, null, null, null);

        assertThat(waiting.waitingForRetry()).isTrue();
        assertThat(fresh.waitingForRetry()).isFalse();
    }

    @Test
    void locatorsAndDraftsCopyTheirLists() {
        java.util.ArrayList<String> path = new java.util.ArrayList<>(List.of("一"));
        SourceLocator locator = new SourceLocator("doc_1", "a.pdf", 1, 2, path);
        path.add("二");

        assertThat(locator.headingPath()).containsExactly("一");
    }
}
