package cn.refinex.meridian.controlplane.environment.domain;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个修订的执行配置，与 engine-spi 的 {@code EnvironmentSpec} 对应（架构设计「环境档位」「沙箱出网」）：{@code sandbox} 档必须有规格，
 * {@code none} 档不能有；出网白名单是域名，{@code *.} 前缀匹配任意层级子域名（不含域名本身，与 runtime 的 {@code EgressAllowlist} 相同），
 * 不接受 IP、端口与路径。白名单按小写去重、保持顺序。
 *
 * @param tier 执行档位：{@code none} 或 {@code sandbox}
 * @param size 沙箱规格：{@code small}、{@code medium}、{@code large}；{@code none} 档为空
 * @param egressAllowlist 出网白名单
 */
public record EnvironmentConfig(String tier, String size, List<String> egressAllowlist) {

    /** 白名单的条目上限。 */
    public static final int MAX_ALLOWLIST = 100;

    /** 执行档位的取值。 */
    public static final List<String> TIERS = List.of("none", "sandbox");

    /** 沙箱规格的取值，各对应一套预部署的沙箱模板与预热池。 */
    public static final List<String> SIZES = List.of("small", "medium", "large");

    /** 一个域名标签：字母或数字开头结尾，中间可以有短横线，至多 63 个字符。 */
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

    /** 白名单条目：可选的 {@code *.} 前缀加一个或多个标签；单标签允许（集群内的服务名）。 */
    private static final Pattern DOMAIN = Pattern.compile("^(?:\\*\\.)?" + LABEL + "(?:\\." + LABEL + ")*$");

    /** 全是数字与点的条目是 IPv4 地址，不接受。 */
    private static final Pattern IPV4 = Pattern.compile("^[0-9.]+$");

    /**
     * 创建并规范化配置。
     *
     * @throws ValidationException 档位、规格或白名单不合法时，字段错误指向 {@code tier}、{@code size}、{@code egress_allowlist.N}
     */
    public EnvironmentConfig {
        Objects.requireNonNull(tier, "tier");
        List<FieldViolation> violations = new ArrayList<>();
        if (!TIERS.contains(tier)) {
            violations.add(new FieldViolation("tier", "档位只能是 none 或 sandbox"));
        }
        if ("sandbox".equals(tier) && size == null) {
            violations.add(new FieldViolation("size", "sandbox 档必须选择规格"));
        }
        if ("none".equals(tier) && size != null) {
            violations.add(new FieldViolation("size", "none 档不能选择规格"));
        }
        if (size != null && !SIZES.contains(size)) {
            violations.add(new FieldViolation("size", "规格只能是 small、medium 或 large"));
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        List<String> given = egressAllowlist == null ? List.of() : egressAllowlist;
        if (given.size() > MAX_ALLOWLIST) {
            violations.add(new FieldViolation("egress_allowlist", "白名单至多 " + MAX_ALLOWLIST + " 个域名"));
        }
        for (int i = 0; i < given.size(); i++) {
            String domain = given.get(i) == null ? "" : given.get(i).trim().toLowerCase(Locale.ROOT);
            if (domain.length() > 253
                    || !DOMAIN.matcher(domain).matches()
                    || IPV4.matcher(domain).matches()) {
                violations.add(new FieldViolation(
                        "egress_allowlist." + i, "不是合法的域名（小写，*. 前缀表示子域名，不接受 IP、端口与路径）：" + given.get(i)));
            } else {
                domains.add(domain);
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "环境配置不合法", violations);
        }
        egressAllowlist = List.copyOf(domains);
    }
}
