package cn.refinex.meridian.persistence.jdbc;

import java.util.regex.Pattern;

/**
 * schema 名的校验。本模块的 SQL 需要把服务的 schema（{@code cp}、{@code rt}、{@code kb}）拼进表名，
 * 拼接前必须经过这里校验，防止配置值被注入 SQL。
 */
public final class SchemaNames {

    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private SchemaNames() {}

    /**
     * 校验并返回 schema 名。
     *
     * @param schema schema 名
     * @return 原值
     * @throws IllegalArgumentException 不是小写字母、数字、下划线组成的合法标识符时
     */
    public static String require(String schema) {
        if (schema == null || !NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("非法的 schema 名：" + schema);
        }
        return schema;
    }
}
