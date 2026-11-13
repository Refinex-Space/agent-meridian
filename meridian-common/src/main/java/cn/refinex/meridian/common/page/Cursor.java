package cn.refinex.meridian.common.page;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ValidationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 游标分页的位置：上一页最后一条记录的排序键（keyset），对外编码为不透明的 base64url 字符串，
 * 见架构设计「API 设计」中的分页约定。
 *
 * <p>编码格式：{@code base64url(版本 1 字节 + 校验和 8 字节 + 载荷)}，载荷是排序键的 UTF-8 文本，
 * 键之间以控制字符 U+001F 分隔。校验和取载荷 SHA-256 的前 8 字节，用来拒绝被截断、改动或伪造的游标，
 * 让坏游标在入口得到 400 而不是走到 SQL。它不是签名：游标只决定从哪里继续读，
 * 数据可见范围由租户作用域仓储保证，所以不需要密钥。
 *
 * @param keys 排序键，按 ORDER BY 的列顺序排列，例如 {@code ["2026-11-07T05:49:49.123Z", "ses_01J..."]}；不能为空
 */
public record Cursor(List<String> keys) {

    /** 当前编码版本；格式变化时递增，旧版本的游标解码时被拒绝。 */
    private static final byte VERSION = 1;

    /** 校验和长度（字节）。 */
    private static final int CHECKSUM_LENGTH = 8;

    /** 排序键之间的分隔符：控制字符不会出现在 ID、时间与数字中。 */
    private static final char KEY_SEPARATOR = '\u001F';

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * 创建游标。
     *
     * @throws IllegalArgumentException 没有排序键，或某个键为空、含分隔符时
     */
    public Cursor {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("游标至少需要一个排序键");
        }
        for (String key : keys) {
            if (key == null || key.indexOf(KEY_SEPARATOR) >= 0) {
                throw new IllegalArgumentException("游标的排序键不能为空，也不能包含 U+001F");
            }
        }
        keys = List.copyOf(keys);
    }

    /**
     * 以若干排序键创建游标。
     *
     * @param keys 排序键，按 ORDER BY 的列顺序
     * @return 游标
     */
    public static Cursor of(String... keys) {
        return new Cursor(Arrays.asList(keys));
    }

    /**
     * 返回第 {@code index} 个排序键。游标来自外部：别的列表发出的游标校验和同样正确，但键的个数可能不够（R2 审查 A-02）。
     *
     * @param index 从 0 开始的位置
     * @return 排序键
     * @throws ValidationException 游标没有这个位置的键时，错误码 {@link CommonErrorCode#INVALID_CURSOR}
     */
    public String key(int index) {
        if (index < 0 || index >= keys.size()) {
            throw invalid();
        }
        return keys.get(index);
    }

    /**
     * 第 {@code index} 个排序键按 RFC 3339 时间点解析。
     *
     * @param index 从 0 开始的位置
     * @return 时间点
     * @throws ValidationException 没有这个键或不是时间点时，错误码 {@link CommonErrorCode#INVALID_CURSOR}
     */
    public Instant instantKey(int index) {
        try {
            return Instant.parse(key(index));
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    /**
     * 第 {@code index} 个排序键按十进制整数解析。
     *
     * @param index 从 0 开始的位置
     * @return 整数
     * @throws ValidationException 没有这个键或不是 {@code int} 范围的整数时，错误码 {@link CommonErrorCode#INVALID_CURSOR}
     */
    public int intKey(int index) {
        try {
            return Integer.parseInt(key(index));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    /**
     * 编码为对外的不透明字符串。
     *
     * @return base64url 编码（无填充）的游标
     */
    public String encode() {
        byte[] payload = String.join(String.valueOf(KEY_SEPARATOR), keys).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + CHECKSUM_LENGTH + payload.length);
        buffer.put(VERSION).put(checksum(payload)).put(payload);
        return ENCODER.encodeToString(buffer.array());
    }

    /**
     * 解码调用方传回的游标。
     *
     * @param token {@link #encode()} 的输出
     * @return 游标
     * @throws ValidationException 格式、版本或校验和不对时，错误码 {@link CommonErrorCode#INVALID_CURSOR}
     */
    public static Cursor decode(String token) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(token);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
        if (bytes.length <= 1 + CHECKSUM_LENGTH || bytes[0] != VERSION) {
            throw invalid();
        }
        byte[] expected = Arrays.copyOfRange(bytes, 1, 1 + CHECKSUM_LENGTH);
        byte[] payload = Arrays.copyOfRange(bytes, 1 + CHECKSUM_LENGTH, bytes.length);
        if (!MessageDigest.isEqual(expected, checksum(payload))) {
            throw invalid();
        }
        return new Cursor(split(new String(payload, StandardCharsets.UTF_8)));
    }

    private static List<String> split(String payload) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = payload.indexOf(KEY_SEPARATOR, start)) >= 0) {
            result.add(payload.substring(start, index));
            start = index + 1;
        }
        result.add(payload.substring(start));
        return result;
    }

    private static byte[] checksum(byte[] payload) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(payload), CHECKSUM_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }

    private static ValidationException invalid() {
        return new ValidationException(CommonErrorCode.INVALID_CURSOR, "分页游标无效，请从第一页重新查询");
    }
}
