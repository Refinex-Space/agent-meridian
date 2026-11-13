package cn.refinex.meridian.controlplane.metering.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 用量明细 CSV 的字段转义（RFC 4180）。 */
class UsageRecordsCsvTest {

    @Test
    void fieldsWithSeparatorsAreQuoted() {
        assertThat(UsageRecords.field(null)).isEmpty();
        assertThat(UsageRecords.field("ses_1")).isEqualTo("ses_1");
        assertThat(UsageRecords.field("{\"a\":1,\"b\":2}")).isEqualTo("\"{\"\"a\"\":1,\"\"b\"\":2}\"");
        assertThat(UsageRecords.field("两行\n文本")).isEqualTo("\"两行\n文本\"");
        assertThat(UsageRecords.COLUMNS).startsWith("id", "occurred_at", "kind").endsWith("cost", "attributes");
    }
}
