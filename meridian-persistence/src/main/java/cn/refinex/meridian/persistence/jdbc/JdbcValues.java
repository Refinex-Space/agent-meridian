package cn.refinex.meridian.persistence.jdbc;

import cn.refinex.meridian.common.json.JsonMappers;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL 特有类型与 Java 类型之间的转换：{@code jsonb}、{@code text[]}、{@code timestamptz}。
 *
 * <ul>
 *   <li>写 {@code timestamptz}：PostgreSQL 驱动不接受 {@link Instant}，换成 UTC 的 {@link OffsetDateTime}。
 *       {@code TenantScopedRepository} 的参数自动做这一步，{@code SystemRepository} 的子类显式调用 {@link #toSql(Object)}。
 *   <li>写 {@code jsonb}：包装成类型为 {@code jsonb} 的 {@link PGobject}，SQL 里不需要 {@code CAST}。
 *   <li>写 {@code text[]}：传 {@code String[]}；不要传 {@code List}，Spring 会把集合展开成 IN 列表。
 * </ul>
 *
 * <p>JSON 的序列化与解析使用 {@code common.json} 的约定：写出与 API 一致，读取时忽略未知字段，
 * 已存储的历史数据多出字段时不会失败。
 */
public final class JdbcValues {

    /** JSON 中 NUL 字符的转义序列，{@code jsonb} 不接受。 */
    private static final String NUL_ESCAPE = "\\u0000";

    /** 替换 NUL 用的 U+FFFD 的转义序列。 */
    private static final String REPLACEMENT_ESCAPE = "\\ufffd";

    /** U+FFFD（REPLACEMENT CHARACTER）。 */
    private static final char REPLACEMENT_CHARACTER = '\uFFFD';

    private JdbcValues() {}

    /**
     * 把参数值转换为驱动能接受的形式：{@link Instant} 转为 UTC 的 {@link OffsetDateTime}，其他原样返回。
     *
     * @param value 参数值，可为空
     * @return 转换后的值
     */
    public static Object toSql(Object value) {
        return value instanceof Instant instant ? timestamptz(instant) : value;
    }

    /**
     * 把时间点转换为 {@code timestamptz} 参数。
     *
     * @param instant 时间点，可为空
     * @return UTC 偏移的时间，入参为空时返回空
     */
    public static OffsetDateTime timestamptz(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * 读取 {@code timestamptz} 列。
     *
     * @param rs 结果集
     * @param column 列名
     * @return 时间点，列为 NULL 时返回空
     * @throws SQLException 读取失败时
     */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * 把 JSON 文本包装为 {@code jsonb} 参数。PostgreSQL 的 {@code jsonb} 拒绝字符串中的 NUL 字符（转义序列
     * <code>&#92;u0000</code>），模型输出或工具结果含 NUL 时整个写入会失败，所以先替换为 U+FFFD（登记事项 D11，ADR-0028）。
     *
     * @param json JSON 文本，可为空
     * @return {@code jsonb} 参数
     */
    public static PGobject jsonb(String json) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(withoutNul(json));
        } catch (SQLException e) {
            // PGobject.setValue 只是赋值，声明的 SQLException 不会发生
            throw new IllegalStateException(e);
        }
        return object;
    }

    /**
     * 把 JSON 文本中的 NUL 字符替换为 U+FFFD：转义序列 <code>&#92;u0000</code> 换成 <code>&#92;ufffd</code>，未转义的 NUL
     * 换成该字符本身。只识别真正的转义序列，<code>&#92;&#92;u0000</code>（反斜杠本身被转义，后接普通文本）保持不变。
     *
     * @param json JSON 文本，可为空
     * @return 替换后的文本；没有 NUL 时返回原对象
     */
    static String withoutNul(String json) {
        if (json == null || (json.indexOf(NUL_ESCAPE) < 0 && json.indexOf('\0') < 0)) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length());
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\0') {
                out.append(REPLACEMENT_CHARACTER);
                i++;
            } else if (c == '\\' && i + 1 < json.length()) {
                if (json.startsWith(NUL_ESCAPE, i)) {
                    out.append(REPLACEMENT_ESCAPE);
                    i += NUL_ESCAPE.length();
                } else {
                    // 原样保留整个转义序列的前两个字符，转义的反斜杠因此不会被误认为下一个转义的开头
                    out.append(c).append(json.charAt(i + 1));
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 把对象序列化为 {@code jsonb} 参数，字段名为 snake_case。
     *
     * @param value 要序列化的对象，可为空
     * @return {@code jsonb} 参数
     */
    public static PGobject jsonbOf(Object value) {
        return jsonb(value == null ? null : JsonMappers.lenient().writeValueAsString(value));
    }

    /**
     * 读取 {@code jsonb} 列并解析为指定类型。
     *
     * @param rs 结果集
     * @param column 列名
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 解析结果，列为 NULL 时返回空
     * @throws SQLException 读取失败时
     */
    public static <T> T readJsonb(ResultSet rs, String column, Class<T> type) throws SQLException {
        String json = rs.getString(column);
        return json == null ? null : JsonMappers.lenient().readValue(json, type);
    }

    /**
     * 把字符串集合转换为 {@code text[]} 参数。
     *
     * @param values 字符串集合，可为空
     * @return 字符串数组
     */
    public static String[] textArray(Collection<String> values) {
        return values == null ? null : values.toArray(String[]::new);
    }

    /**
     * 读取 {@code text[]} 列。
     *
     * @param rs 结果集
     * @param column 列名
     * @return 字符串列表，列为 NULL 时返回空列表
     * @throws SQLException 读取失败时
     */
    public static List<String> readTextArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
