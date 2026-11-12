package cn.refinex.meridian.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdsTest {

    @ParameterizedTest
    @EnumSource(IdPrefix.class)
    void newIdCarriesPrefixAndParsesBack(IdPrefix prefix) {
        String id = Ids.newId(prefix);

        assertThat(id).matches(prefix.value() + "_[0-9A-HJKMNP-TV-Z]{26}");
        Ids.ParsedId parsed = Ids.parse(id, prefix);
        assertThat(parsed.prefix()).isEqualTo(prefix);
        assertThat(id).endsWith(parsed.ulid());
        assertThat(Ids.isValid(id, prefix)).isTrue();
    }

    @Test
    void prefixesMatchArchitectureTable() {
        assertThat(IdPrefix.values())
                .extracting(IdPrefix::value)
                .containsExactlyInAnyOrder(
                        "ten", "prj", "key", "agt", "agv", "env", "mdl", "prv", "sec", "skl", "ses", "thr", "trn",
                        "evt", "tiv", "act", "kb", "doc", "fil", "mcp", "mbr", "rbd", "prb", "prl", "bdg", "bal", "whk",
                        "whd");
    }

    @Test
    void idsAreTimeOrderedEvenWithinTheSameMillisecond() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId(IdPrefix.EVENT));
        }

        assertThat(ids).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void parsedTimestampIsGenerationTime() {
        Instant before = Instant.now().minusMillis(1);
        Ids.ParsedId parsed = Ids.parse(Ids.newId(IdPrefix.SESSION));
        Instant after = Instant.now().plusMillis(1);

        assertThat(parsed.createdAt()).isBetween(before, after);
    }

    @Test
    void wrongPrefixIsRejected() {
        String sessionId = Ids.newId(IdPrefix.SESSION);

        assertThatThrownBy(() -> Ids.parse(sessionId, IdPrefix.THREAD))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).errorCode()).isEqualTo(CommonErrorCode.INVALID_ID));
        assertThat(Ids.isValid(sessionId, IdPrefix.THREAD)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "01JC8Z4V7Q9X3M2N6P0R5T8W1Y",
                "_01JC8Z4V7Q9X3M2N6P0R5T8W1Y",
                "xyz_01JC8Z4V7Q9X3M2N6P0R5T8W1Y",
                "SES_01JC8Z4V7Q9X3M2N6P0R5T8W1Y",
                "ses_01JC8Z4V7Q9X3M2N6P0R5T8W1",
                "ses_01JC8Z4V7Q9X3M2N6P0R5T8W1YZ",
                "ses_01JC8Z4V7Q9X3M2N6P0R5T8WIU",
                "ses-01JC8Z4V7Q9X3M2N6P0R5T8W1Y"
            })
    void malformedIdsAreRejected(String id) {
        assertThatThrownBy(() -> Ids.parse(id)).isInstanceOf(ValidationException.class);
    }

    @Test
    void nullIdIsRejected() {
        assertThatThrownBy(() -> Ids.parse(null)).isInstanceOf(ValidationException.class);
        assertThat(Ids.isValid(null, IdPrefix.SESSION)).isFalse();
    }

    @Test
    void rawUlidsAreUniqueAndOrdered() throws InterruptedException {
        Set<String> seen = new HashSet<>();
        String first = Ids.newUlid();
        Thread.sleep(Duration.ofMillis(2));
        String second = Ids.newUlid();

        assertThat(first).hasSize(26);
        assertThat(second).isGreaterThan(first);
        assertThat(seen.add(first) && seen.add(second)).isTrue();
    }
}
