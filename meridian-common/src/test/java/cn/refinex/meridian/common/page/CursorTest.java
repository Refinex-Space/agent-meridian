package cn.refinex.meridian.common.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void encodeAndDecodeAreInverse() {
        Cursor cursor = Cursor.of("2026-11-07T05:49:49.123Z", "ses_01JC8Z4V7Q9X3M2N6P0R5T8W1Y", "差旅", "");

        String token = cursor.encode();

        assertThat(token).matches("[A-Za-z0-9_-]+");
        assertThat(Cursor.decode(token)).isEqualTo(cursor);
        assertThat(Cursor.decode(token).key(2)).isEqualTo("差旅");
    }

    @Test
    void singleKeyRoundTrips() {
        Cursor cursor = Cursor.of("42");

        assertThat(Cursor.decode(cursor.encode()).keys()).containsExactly("42");
    }

    @Test
    void everySingleCharacterChangeIsRejected() {
        String token = Cursor.of("2026-11-07T05:49:49.123Z", "ses_01JC8Z4V7Q9X3M2N6P0R5T8W1Y")
                .encode();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

        for (int i = 0; i < token.length(); i++) {
            char replacement = alphabet.charAt((alphabet.indexOf(token.charAt(i)) + 1) % alphabet.length());
            String tampered = token.substring(0, i) + replacement + token.substring(i + 1);
            // 末字符只编码了部分比特，替换后可能解码出同样的字节；这种情况不算篡改
            if (Arrays.equals(
                    Base64.getUrlDecoder().decode(tampered),
                    Base64.getUrlDecoder().decode(token))) {
                continue;
            }
            assertThatThrownBy(() -> Cursor.decode(tampered))
                    .as("位置 %d 被篡改", i)
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void forgedPayloadWithoutValidChecksumIsRejected() {
        byte[] original = Base64.getUrlDecoder().decode(Cursor.of("ses_A").encode());
        byte[] forged = original.clone();
        forged[forged.length - 1] = (byte) 'B';

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(forged);

        assertThatThrownBy(() -> Cursor.decode(token))
                .isInstanceOf(ValidationException.class)
                .satisfies(e ->
                        assertThat(((ValidationException) e).errorCode()).isEqualTo(CommonErrorCode.INVALID_CURSOR));
    }

    @Test
    void aCursorFromAnotherListIsInvalidInsteadOfAServerError() {
        // R2 审查 A-02：别的列表发出的游标校验和正确，但键的个数、类型不对
        Cursor foreign = Cursor.decode(Cursor.of("prj_A").encode());

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable read :
                List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> foreign.key(1), () -> foreign.instantKey(0), () -> foreign.intKey(0))) {
            assertThatThrownBy(read)
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                            .isEqualTo(CommonErrorCode.INVALID_CURSOR));
        }
        Cursor typed = Cursor.decode(Cursor.of("2026-11-13T00:00:00Z", "12").encode());
        assertThat(typed.instantKey(0)).isEqualTo(java.time.Instant.parse("2026-11-13T00:00:00Z"));
        assertThat(typed.intKey(1)).isEqualTo(12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64!", "AQ", "AAAAAAAAAAAAAA"})
    void malformedTokensAreRejected(String token) {
        assertThatThrownBy(() -> Cursor.decode(token)).isInstanceOf(ValidationException.class);
    }

    @Test
    void nullTokenIsRejected() {
        assertThatThrownBy(() -> Cursor.decode(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void plainBase64OfKeysIsNotAcceptedAsCursor() {
        String naive = Base64.getUrlEncoder().withoutPadding().encodeToString("ses_A".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Cursor.decode(naive)).isInstanceOf(ValidationException.class);
    }

    @Test
    void keysMustBePresentAndWithoutSeparator() {
        assertThatThrownBy(Cursor::of).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cursor.of("a\u001Fb")).isInstanceOf(IllegalArgumentException.class);
    }
}
