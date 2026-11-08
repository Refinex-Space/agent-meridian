package cn.refinex.meridian.runtime.modelrouting.domain;

import java.util.Locale;

/** 预算的四个级别（架构设计「计量与预算」），顺序即预扣时检查的顺序。 */
public enum BudgetLevel {
    /** 租户。 */
    TENANT,
    /** 项目。 */
    PROJECT,
    /** API Key。 */
    API_KEY,
    /** 会话。 */
    SESSION;

    /**
     * 配置、Redis 键与指标标签中的取值。
     *
     * @return 小写的常量名，例如 {@code api_key}
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 按取值解析。
     *
     * @param value 取值，不区分大小写
     * @return 级别
     * @throws IllegalArgumentException 取值未知时
     */
    public static BudgetLevel of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
