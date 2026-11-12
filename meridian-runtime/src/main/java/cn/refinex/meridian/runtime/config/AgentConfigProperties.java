package cn.refinex.meridian.runtime.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 智能体配置的来源与缓存（{@code meridian.runtime.agent-config}，ADR-0047）。
 *
 * @param source 来源：{@value #CONTROL_PLANE}（默认，经 control-plane 的内部接口读取已发布的版本、环境、技能、租户设置与策略）或
 *     {@value #FILE}（开发配置目录 {@code meridian.runtime.dev-agent-config.directory} 与 {@code rt.permission_rules}，只允许 dev、
 *     test profile）
 * @param cacheTtl 会变的配置（当前版本、当前修订、租户设置、策略）的缓存时间：{@code config.invalidate} 丢失时的兜底
 * @param immutableCacheTtl 不可变的配置（按 ID 的已发布版本、指定修订、技能版本）的缓存时间
 */
@ConfigurationProperties("meridian.runtime.agent-config")
public record AgentConfigProperties(
        @DefaultValue(CONTROL_PLANE) String source,
        @DefaultValue("60s") Duration cacheTtl,
        @DefaultValue("10m") Duration immutableCacheTtl) {

    /** 经 control-plane 读取。 */
    public static final String CONTROL_PLANE = "control-plane";

    /** 读开发配置目录。 */
    public static final String FILE = "file";

    /**
     * 校验来源的取值。
     *
     * @throws IllegalArgumentException 取值未知时
     */
    public AgentConfigProperties {
        if (!CONTROL_PLANE.equals(source) && !FILE.equals(source)) {
            throw new IllegalArgumentException(
                    "meridian.runtime.agent-config.source 只能是 control-plane 或 file：" + source);
        }
    }
}
