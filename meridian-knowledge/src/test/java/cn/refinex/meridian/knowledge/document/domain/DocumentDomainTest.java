package cn.refinex.meridian.knowledge.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeErrorCode;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 文档的领域规则：格式归类、访问标签、对象键、状态与错误码（ADR-0051）。 */
class DocumentDomainTest {

    @ParameterizedTest
    @CsvSource({
        "text/x-web-markdown,MARKDOWN",
        "text/markdown; charset=UTF-8,MARKDOWN",
        "text/plain; charset=ISO-8859-1,TEXT",
        "text/csv,TEXT",
        "application/pdf,PDF",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document,OFFICE",
        "application/vnd.ms-excel.sheet.macroenabled.12,OFFICE",
        "application/vnd.oasis.opendocument.text,OFFICE",
        "application/msword,OFFICE",
        "text/html,OFFICE",
        "application/rtf,OFFICE",
        "image/png,IMAGE",
        "image/jpeg,IMAGE"
    })
    void formatsFollowTheDetectedMediaType(String mediaType, DocumentFormat expected) {
        assertThat(DocumentFormat.of(mediaType)).contains(expected);
    }

    @Test
    void unknownTypesAreUnsupported() {
        assertThat(DocumentFormat.of("application/zip")).isEmpty();
        assertThat(DocumentFormat.of("application/octet-stream")).isEmpty();
        assertThat(DocumentFormat.of(null)).isEmpty();
    }

    @Test
    void aclTagsAreTrimmedDeduplicatedAndValidated() {
        assertThat(AclTags.parse(null)).isEmpty();
        assertThat(AclTags.parse(" ")).isEmpty();
        assertThat(AclTags.parse("dept:finance, level:internal,dept:finance"))
                .containsExactly("dept:finance", "level:internal");
        assertThatThrownBy(() -> AclTags.parse("ok,有空 格")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> AclTags.parse("a,,b")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> AclTags.parse("x".repeat(129))).isInstanceOf(ValidationException.class);
        StringBuilder many = new StringBuilder("t0");
        for (int i = 1; i <= AclTags.MAX_TAGS; i++) {
            many.append(",t").append(i);
        }
        assertThatThrownBy(() -> AclTags.parse(many.toString()))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .singleElement()
                                .satisfies(v -> assertThat(v.field()).isEqualTo("acl_tags")));
    }

    @Test
    void objectKeysNestUnderTheKnowledgeBase() {
        assertThat(Document.objectKey("ten_1", "prj_1", "kb_1", "doc_1")).isEqualTo("kb/ten_1/prj_1/kb_1/doc_1");
        java.util.ArrayList<String> tags = new java.util.ArrayList<>(Collections.singletonList("a"));
        Document document = new Document(
                "doc_1",
                "ten_1",
                "prj_1",
                "kb_1",
                "a.md",
                "text/markdown",
                1,
                "h",
                "k",
                tags,
                DocumentStatus.QUEUED,
                null,
                0,
                0,
                "key_1",
                null,
                null);
        tags.add("b");
        assertThat(document.aclTags()).containsExactly("a");
    }

    @Test
    void statusesRoundTripAndErrorCodesAreRegistered() {
        for (DocumentStatus status : DocumentStatus.values()) {
            assertThat(DocumentStatus.of(status.value())).isEqualTo(status);
        }
        ErrorCodes.verify(KnowledgeErrorCode.values());
    }
}
