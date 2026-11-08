package cn.refinex.meridian.runtime.egress.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 环境的出网白名单（{@code EnvironmentSpec.egressAllowlist}，架构设计「沙箱出网」）。
 *
 * <p>条目有两种写法，都不区分大小写、忽略末尾的 {@code .}：
 *
 * <ul>
 *   <li>主机名或 IP 字面量，例如 {@code pypi.org}：只匹配它本身。
 *   <li>{@code *.} 加域名，例如 {@code *.example.com}：匹配任意层级的子域名（{@code a.example.com}、{@code a.b.example.com}），不匹配
 *       {@code example.com} 本身；需要时两条都写。
 * </ul>
 *
 * <p>白名单只限定主机，不限定端口。不合法的条目（包括单独的 {@code *}、中间带 {@code *}、带端口或路径）不匹配任何目标，
 * 由 {@link #ignored()} 列出；空白名单表示无出网（架构原则"默认拒绝"）。线程安全：不可变。
 */
public final class EgressAllowlist {

    private static final String WILDCARD = "*.";

    private final Set<String> exact;
    private final List<String> suffixes;
    private final List<String> ignored;

    private EgressAllowlist(Set<String> exact, List<String> suffixes, List<String> ignored) {
        this.exact = Set.copyOf(exact);
        this.suffixes = List.copyOf(suffixes);
        // 被忽略的条目原样保留（可能有 null），不能用 List.copyOf
        this.ignored = Collections.unmodifiableList(new ArrayList<>(ignored));
    }

    /**
     * 按环境配置的条目建立白名单。
     *
     * @param entries 条目；可为空
     * @return 白名单
     */
    public static EgressAllowlist of(List<String> entries) {
        Set<String> exact = new HashSet<>();
        List<String> suffixes = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String entry : entries == null ? List.<String>of() : entries) {
            String trimmed = entry == null ? "" : entry.strip();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(WILDCARD)) {
                Optional<String> domain = HostNames.normalize(trimmed.substring(WILDCARD.length()));
                if (domain.isPresent() && domain.get().indexOf(':') < 0) {
                    suffixes.add("." + domain.get());
                } else {
                    ignored.add(entry);
                }
            } else {
                HostNames.normalize(trimmed).ifPresentOrElse(exact::add, () -> ignored.add(entry));
            }
        }
        return new EgressAllowlist(exact, suffixes, ignored);
    }

    /**
     * 目标主机是否在白名单内。
     *
     * @param host 规整后的主机名（{@link EgressTarget#host()}）
     * @return 匹配任一条目时为 true
     */
    public boolean permits(String host) {
        if (exact.contains(host)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (host.length() > suffix.length() && host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 不合法、因而被忽略的条目，原样返回，供调用方记录告警。
     *
     * @return 被忽略的条目
     */
    public List<String> ignored() {
        return ignored;
    }
}
