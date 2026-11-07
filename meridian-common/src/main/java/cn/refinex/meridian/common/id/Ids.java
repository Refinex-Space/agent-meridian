package cn.refinex.meridian.common.id;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ValidationException;
import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;

/**
 * 对外 ID 的生成、解析与校验，格式为 {@code {前缀}_{ULID}}，例如 {@code ses_01JC8Z4V7Q9X3M2N6P0R5T8W1Y}。
 *
 * <p>使用单调 ULID：同一毫秒内生成的 ID 也严格递增，所以 ID 的字典序就是生成顺序，便于索引和排查，
 * 见架构设计「ID 规则」。ULID 的时间部分取系统时钟，这是"注入 {@code Clock}"约定的唯一例外：
 * ID 只要求全局唯一且大致有序，不参与业务时间判断。
 *
 * <p>线程安全：所有方法都可以并发调用。
 */
public final class Ids {

    /** 前缀与 ULID 之间的分隔符。 */
    private static final char SEPARATOR = '_';

    private Ids() {}

    /**
     * 生成一个新的带前缀 ID。
     *
     * @param prefix 对象类型
     * @return 形如 {@code ses_01JC8Z4V7Q9X3M2N6P0R5T8W1Y} 的 ID
     */
    public static String newId(IdPrefix prefix) {
        return prefix.value() + SEPARATOR + UlidCreator.getMonotonicUlid();
    }

    /**
     * 生成一个不带前缀的 ULID，用于不对外暴露、也不在「ID 规则」表中的内部记录（如 outbox 消息、请求 ID）。
     *
     * @return 26 位大写 ULID
     */
    public static String newUlid() {
        return UlidCreator.getMonotonicUlid().toString();
    }

    /**
     * 解析并校验一个带前缀 ID。
     *
     * @param id 待解析的 ID，来自外部输入
     * @param expected 期望的对象类型
     * @return 解析结果
     * @throws ValidationException 格式不对、前缀与期望不符或 ULID 非法时
     */
    public static ParsedId parse(String id, IdPrefix expected) {
        ParsedId parsed = parse(id);
        if (parsed.prefix() != expected) {
            throw invalid(id, "前缀应为 " + expected.value());
        }
        return parsed;
    }

    /**
     * 解析一个带前缀 ID，不限定对象类型。
     *
     * @param id 待解析的 ID，来自外部输入
     * @return 解析结果
     * @throws ValidationException 格式不对、前缀未登记或 ULID 非法时
     */
    public static ParsedId parse(String id) {
        if (id == null) {
            throw invalid(null, "ID 不能为空");
        }
        int separator = id.indexOf(SEPARATOR);
        if (separator <= 0) {
            throw invalid(id, "缺少前缀");
        }
        String prefixText = id.substring(0, separator);
        String ulidText = id.substring(separator + 1);
        IdPrefix prefix = findPrefix(prefixText);
        if (prefix == null) {
            throw invalid(id, "未知前缀 " + prefixText);
        }
        if (!Ulid.isValid(ulidText)) {
            throw invalid(id, "ULID 非法");
        }
        return new ParsedId(prefix, ulidText, Ulid.from(ulidText).getInstant());
    }

    /**
     * 判断 ID 是否为指定类型的合法 ID，不抛异常。
     *
     * @param id 待检查的 ID，可为空
     * @param expected 期望的对象类型
     * @return 合法时返回 {@code true}
     */
    public static boolean isValid(String id, IdPrefix expected) {
        try {
            return parse(id).prefix() == expected;
        } catch (ValidationException e) {
            return false;
        }
    }

    private static IdPrefix findPrefix(String text) {
        for (IdPrefix prefix : IdPrefix.values()) {
            if (prefix.value().equals(text)) {
                return prefix;
            }
        }
        return null;
    }

    private static ValidationException invalid(String id, String reason) {
        return new ValidationException(CommonErrorCode.INVALID_ID, "ID 格式错误：" + reason + "（" + id + "）");
    }

    /**
     * 解析后的 ID。
     *
     * @param prefix 对象类型
     * @param ulid ULID 部分，26 位
     * @param createdAt ULID 中编码的生成时间，精度为毫秒
     */
    public record ParsedId(IdPrefix prefix, String ulid, Instant createdAt) {}
}
