package cn.refinex.meridian.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class JdbcValuesTest {

    @Test
    void instantsBecomeUtcOffsetDateTimes() {
        Instant instant = Instant.parse("2026-11-07T05:49:49.123456Z");

        assertThat(JdbcValues.toSql(instant))
                .isEqualTo(OffsetDateTime.of(2026, 11, 7, 5, 49, 49, 123456000, ZoneOffset.UTC));
        assertThat(JdbcValues.toSql("x")).isEqualTo("x");
        assertThat(JdbcValues.toSql(null)).isNull();
        assertThat(JdbcValues.timestamptz(null)).isNull();
    }

    @Test
    void jsonbUsesPlatformJsonConventions() {
        record Payload(String tenantId, int inputTokens) {}

        PGobject jsonb = JdbcValues.jsonbOf(new Payload("ten_A", 3));

        assertThat(jsonb.getType()).isEqualTo("jsonb");
        assertThat(jsonb.getValue()).isEqualTo("{\"tenant_id\":\"ten_A\",\"input_tokens\":3}");
        assertThat(JdbcValues.jsonbOf(Map.of()).getValue()).isEqualTo("{}");
    }

    @Test
    void nulCharactersAreReplacedBecauseJsonbRejectsThem() {
        String escapedNul = "{\"text\":\"a\\u0000b\"}";
        String escapedBackslash = "{\"text\":\"\\\\u0000\"}";
        String rawNul = "{\"text\":\"a\u0000b\"}";

        assertThat(JdbcValues.jsonb(escapedNul).getValue()).isEqualTo("{\"text\":\"a\\ufffdb\"}");
        assertThat(JdbcValues.jsonb(escapedBackslash).getValue())
                .as("被转义的反斜杠后面的 u0000 是普通文本")
                .isEqualTo(escapedBackslash);
        assertThat(JdbcValues.jsonb(rawNul).getValue()).isEqualTo("{\"text\":\"a\uFFFDb\"}");
        assertThat(JdbcValues.jsonbOf(Map.of("text", "x\u0000y")).getValue()).isEqualTo("{\"text\":\"x\\ufffdy\"}");
        assertThat(JdbcValues.jsonb(null).getValue()).isNull();
    }

    @Test
    void textArraysAreJavaArrays() {
        assertThat(JdbcValues.textArray(List.of("a", "b"))).containsExactly("a", "b");
        assertThat(JdbcValues.textArray(null)).isNull();
    }

    @Test
    void schemaNamesAreValidated() {
        assertThat(SchemaNames.require("rt")).isEqualTo("rt");
        assertThatThrownBy(() -> SchemaNames.require("RT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaNames.require("rt; DROP TABLE x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaNames.require(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
