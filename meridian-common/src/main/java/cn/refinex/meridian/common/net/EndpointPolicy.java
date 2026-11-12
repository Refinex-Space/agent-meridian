package cn.refinex.meridian.common.net;

import cn.refinex.meridian.common.net.AddressPolicy.AddressClass;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 服务端代租户访问的端点（供应商的 {@code base_url}、联网搜索后端）的地址策略（架构设计「凭据」，ADR-0045 第 5 条，登记事项 D52）。
 *
 * <p>只允许 {@code http}、{@code https}，不允许用户信息；主机按 IP 字面量（{@link AddressPolicy#literal}）或 DNS 解析，每个地址都要通过：
 *
 * <ul>
 *   <li>公网地址放行。
 *   <li>私有网段落在 {@link #privateNetworks()} 中才放行（企业内的私有化推理服务），默认全部私有网段。
 *   <li>回环只在 {@link #allowLoopback()} 为真时放行（开发与测试的脚本化模型服务）。
 *   <li>链路本地、云元数据、未指定、组播、保留段始终拒绝。
 * </ul>
 *
 * <p>线程安全：不可变。解析器由调用方给出，测试可以替换。
 *
 * @param allowLoopback 是否放行回环地址
 * @param privateNetworks 放行的私有网段（CIDR）；空列表表示私有地址一律拒绝
 */
public record EndpointPolicy(boolean allowLoopback, List<String> privateNetworks) {

    /** 默认放行的私有网段：RFC 1918、CGNAT 与 IPv6 唯一本地地址。 */
    public static final List<String> DEFAULT_PRIVATE_NETWORKS =
            List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7");

    /** 生产默认：不放行回环，放行全部私有网段。 */
    public static final EndpointPolicy DEFAULT = new EndpointPolicy(false, DEFAULT_PRIVATE_NETWORKS);

    /**
     * 创建策略并校验网段的写法。
     *
     * @throws IllegalArgumentException 网段写法不合法时
     */
    public EndpointPolicy {
        privateNetworks = privateNetworks == null ? List.of() : List.copyOf(privateNetworks);
        for (String cidr : privateNetworks) {
            AddressPolicy.contains(cidr, InetAddress.getLoopbackAddress());
        }
    }

    /** 主机名解析器：返回主机的全部地址。 */
    @FunctionalInterface
    public interface Resolver {

        /** 系统 DNS。 */
        Resolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

        /**
         * 解析主机名。
         *
         * @param host 主机名
         * @return 全部地址，不为空
         * @throws UnknownHostException 解析失败时
         */
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /**
     * 用系统 DNS 校验一个端点。
     *
     * @param url 端点 URL
     * @return 不允许时的原因（中文，可以返回给配置它的管理员）；允许时为空
     */
    public Optional<String> violation(String url) {
        return violation(url, Resolver.SYSTEM);
    }

    /**
     * 校验一个端点。
     *
     * @param url 端点 URL
     * @param resolver 主机名解析器
     * @return 不允许时的原因；允许时为空
     */
    public Optional<String> violation(String url, Resolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (url == null || url.isBlank()) {
            return Optional.of("端点不能为空");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return Optional.of("端点不是合法的 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("端点只能是 http 或 https 地址");
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            return Optional.of("端点缺少主机");
        }
        if (authority.contains("@")) {
            return Optional.of("端点不能包含用户信息");
        }
        String host = host(authority);
        if (host.isEmpty()) {
            return Optional.of("端点缺少主机");
        }
        List<InetAddress> addresses;
        try {
            Optional<InetAddress> literal = AddressPolicy.literal(host);
            addresses = literal.isPresent() ? List.of(literal.get()) : resolver.resolve(host);
        } catch (IllegalArgumentException e) {
            return Optional.of("端点的主机不是合法的地址：" + host);
        } catch (UnknownHostException e) {
            return Optional.of("端点的主机无法解析：" + host);
        }
        for (InetAddress address : addresses) {
            Optional<String> rejected = check(host, address);
            if (rejected.isPresent()) {
                return rejected;
            }
        }
        return Optional.empty();
    }

    /**
     * 单个地址是否放行。
     *
     * @param address 地址
     * @return 放行时为 true
     */
    public boolean permits(InetAddress address) {
        return check("", address).isEmpty();
    }

    private Optional<String> check(String host, InetAddress address) {
        AddressClass type = AddressPolicy.classify(address);
        return switch (type) {
            case PUBLIC -> Optional.empty();
            case LOOPBACK -> allowLoopback ? Optional.empty() : rejected(host, address, "回环地址");
            case PRIVATE ->
                privateNetworks.stream().anyMatch(cidr -> AddressPolicy.contains(cidr, address))
                        ? Optional.empty()
                        : rejected(host, address, "未放行的内网地址");
            case LINK_LOCAL -> rejected(host, address, "链路本地地址");
            case METADATA -> rejected(host, address, "云元数据地址");
            case UNSPECIFIED, MULTICAST, RESERVED -> rejected(host, address, "保留地址");
        };
    }

    private static Optional<String> rejected(String host, InetAddress address, String what) {
        return Optional.of("端点 " + host + " 解析到" + what + " " + address.getHostAddress() + "，不允许访问");
    }

    /** 从 authority 取主机：{@code java.net.URI} 对 {@code 127.1} 这类主机给不出 {@code getHost()}。 */
    private static String host(String authority) {
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end < 0 ? "" : authority.substring(0, end + 1);
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }
}
