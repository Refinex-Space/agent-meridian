package cn.refinex.meridian.controlplane.metering.domain;

import java.util.Locale;

/** 用量报表的聚合维度（ADR-0048 第 9 条）；计量种类与币种总是参与分组，不在这里。 */
public enum UsageDimension {
    /** UTC 日期。 */
    DAY,
    /** 项目（只有管理接口的租户报表有意义）。 */
    PROJECT,
    /** 模型。 */
    MODEL,
    /** 智能体。 */
    AGENT,
    /** API Key。 */
    API_KEY;

    /**
     * 接口中的取值。
     *
     * @return 小写的常量名
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 按取值解析。
     *
     * @param value 取值
     * @return 维度
     * @throws IllegalArgumentException 取值未知时
     */
    public static UsageDimension of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
