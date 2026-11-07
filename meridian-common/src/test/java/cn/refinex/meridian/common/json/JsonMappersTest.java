package cn.refinex.meridian.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.page.PageResult;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

class JsonMappersTest {

    record Sample(String sessionId, Instant createdAt, OffsetDateTime expiresAt, Duration timeout) {}

    @Test
    void writesSnakeCaseAndRfc3339Utc() {
        Sample sample = new Sample(
                "ses_1",
                Instant.parse("2026-11-07T05:49:49.123Z"),
                OffsetDateTime.of(2026, 11, 7, 13, 49, 49, 0, ZoneOffset.ofHours(8)),
                Duration.ofSeconds(30));

        String json = JsonMappers.strict().writeValueAsString(sample);

        assertThat(json)
                .isEqualTo("{\"session_id\":\"ses_1\",\"created_at\":\"2026-11-07T05:49:49.123Z\","
                        + "\"expires_at\":\"2026-11-07T05:49:49Z\",\"timeout\":\"PT30S\"}");
    }

    @Test
    void readsOffsetTimesAsUtc() {
        Sample sample = JsonMappers.strict()
                .readValue(
                        "{\"session_id\":\"ses_1\",\"created_at\":\"2026-11-07T13:49:49+08:00\","
                                + "\"expires_at\":\"2026-11-07T13:49:49+08:00\",\"timeout\":\"PT1M\"}",
                        Sample.class);

        assertThat(sample.createdAt()).isEqualTo(Instant.parse("2026-11-07T05:49:49Z"));
        assertThat(sample.expiresAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(sample.timeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void strictRejectsUnknownFieldsAndLenientIgnoresThem() {
        String json = "{\"session_id\":\"ses_1\",\"sesion_name\":\"typo\"}";

        assertThatThrownBy(() -> JsonMappers.strict().readValue(json, Sample.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
        assertThat(JsonMappers.lenient().readValue(json, Sample.class).sessionId())
                .isEqualTo("ses_1");
    }

    @Test
    void pageResultUsesApiFieldNames() {
        String json = JsonMappers.strict().writeValueAsString(new PageResult<>(List.of(1), true, "abc"));

        assertThat(json).isEqualTo("{\"data\":[1],\"has_more\":true,\"next_cursor\":\"abc\"}");
    }
}
