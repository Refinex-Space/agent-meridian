package cn.refinex.meridian.common.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SSRF 的地址分类与 IP 字面量解析（架构设计「平台工具」「凭据」，ADR-0035、ADR-0045），纯函数。
 *
 * <p>三件事：
 *
 * <ul>
 *   <li>{@link #classify(InetAddress)}：把地址归入 {@link AddressClass}。私有段（RFC 1918、CGNAT、IPv6 唯一本地）是
 *       {@link AddressClass#PRIVATE}；其中的云元数据地址（阿里云 {@code 100.100.100.200}、AWS {@code fd00:ec2::254}）与链路本地
 *       段的 {@code 169.254.169.254} 单独归为 {@link AddressClass#METADATA}；IPv4 映射、IPv4 兼容、NAT64、6to4 地址按内嵌的
 *       IPv4 判定，Teredo 与本地 NAT64 归为保留（隧道地址可以指向任意内网）。
 *   <li>{@link #isBlocked(InetAddress)}：{@code web_fetch} 的策略，只放行公网地址。供应商端点的策略在 {@link EndpointPolicy}。
 *   <li>{@link #literal(String)}：把 URL 中的主机名按 IP 字面量解析，覆盖 {@code inet_aton} 接受的全部写法（十进制
 *       {@code 2130706433}、八进制 {@code 0177.0.0.1}、十六进制 {@code 0x7f.1}、省略段 {@code 127.1}）与 IPv6 字面量。HTTP 客户端
 *       与操作系统对这些写法的处理不一致，所以平台自己解析、自己判定，不交给 DNS；只由数字和点组成却解析不了的主机名直接拒绝。
 * </ul>
 */
public final class AddressPolicy {

    /** 地址的类别，按判定顺序列出：更具体的段先于包含它的段。 */
    public enum AddressClass {
        /** 公网地址。 */
        PUBLIC,
        /** 私有网段：{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}、CGNAT {@code 100.64/10}、IPv6 唯一本地 {@code fc00::/7}。 */
        PRIVATE,
        /** 回环：{@code 127/8}、{@code ::1}。 */
        LOOPBACK,
        /** 链路本地：{@code 169.254/16}、{@code fe80::/10}。 */
        LINK_LOCAL,
        /** 云厂商的元数据服务地址。 */
        METADATA,
        /** 未指定与"本网络"：{@code 0/8}、{@code ::}。 */
        UNSPECIFIED,
        /** 组播：{@code 224/4}、{@code ff00::/8}。 */
        MULTICAST,
        /** 保留、文档、基准测试、协议分配、广播、废弃的站点本地与隧道段。 */
        RESERVED
    }

    /** 云元数据地址：精确到单个地址，判定先于所在的段。 */
    private static final List<Cidr> METADATA =
            List.of(Cidr.of("169.254.169.254/32"), Cidr.of("100.100.100.200/32"), Cidr.of("fd00:ec2::254/128"));

    /** IPv4 各段的类别，按顺序判定。 */
    private static final List<Range> RANGES_V4 = List.of(
            new Range(Cidr.of("0.0.0.0/8"), AddressClass.UNSPECIFIED),
            new Range(Cidr.of("10.0.0.0/8"), AddressClass.PRIVATE),
            new Range(Cidr.of("100.64.0.0/10"), AddressClass.PRIVATE),
            new Range(Cidr.of("127.0.0.0/8"), AddressClass.LOOPBACK),
            new Range(Cidr.of("169.254.0.0/16"), AddressClass.LINK_LOCAL),
            new Range(Cidr.of("172.16.0.0/12"), AddressClass.PRIVATE),
            new Range(Cidr.of("192.0.0.0/24"), AddressClass.RESERVED),
            new Range(Cidr.of("192.0.2.0/24"), AddressClass.RESERVED),
            new Range(Cidr.of("192.88.99.0/24"), AddressClass.RESERVED),
            new Range(Cidr.of("192.168.0.0/16"), AddressClass.PRIVATE),
            new Range(Cidr.of("198.18.0.0/15"), AddressClass.RESERVED),
            new Range(Cidr.of("198.51.100.0/24"), AddressClass.RESERVED),
            new Range(Cidr.of("203.0.113.0/24"), AddressClass.RESERVED),
            new Range(Cidr.of("224.0.0.0/4"), AddressClass.MULTICAST),
            new Range(Cidr.of("240.0.0.0/4"), AddressClass.RESERVED));

    /** IPv6 各段的类别（不含按内嵌 IPv4 判定的几种），按顺序判定。 */
    private static final List<Range> RANGES_V6 = List.of(
            new Range(Cidr.of("::/128"), AddressClass.UNSPECIFIED),
            new Range(Cidr.of("::1/128"), AddressClass.LOOPBACK),
            new Range(Cidr.of("64:ff9b:1::/48"), AddressClass.RESERVED),
            new Range(Cidr.of("100::/64"), AddressClass.RESERVED),
            new Range(Cidr.of("2001::/32"), AddressClass.RESERVED),
            new Range(Cidr.of("2001:db8::/32"), AddressClass.RESERVED),
            new Range(Cidr.of("fc00::/7"), AddressClass.PRIVATE),
            new Range(Cidr.of("fe80::/10"), AddressClass.LINK_LOCAL),
            new Range(Cidr.of("fec0::/10"), AddressClass.RESERVED),
            new Range(Cidr.of("ff00::/8"), AddressClass.MULTICAST));

    /** IPv4 映射（{@code ::ffff:0:0/96}）。JDK 解析映射地址时直接给出 IPv4，所以网络地址按字节构造。 */
    private static final Cidr MAPPED =
            new Cidr(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0}, 96);

    /** IPv4 兼容（{@code ::/96}，已废弃）。 */
    private static final Cidr COMPATIBLE = Cidr.of("::/96");

    /** NAT64 知名前缀（{@code 64:ff9b::/96}）。 */
    private static final Cidr NAT64 = Cidr.of("64:ff9b::/96");

    /** 6to4（{@code 2002::/16}），第 3 到 6 字节是 IPv4。 */
    private static final Cidr SIX_TO_FOUR = Cidr.of("2002::/16");

    /** 只由数字、点和十六进制前缀组成的主机名，按 IPv4 字面量处理。 */
    private static final Pattern NUMERIC_HOST = Pattern.compile("^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*\\.?$");

    private AddressPolicy() {}

    /**
     * 地址的类别。
     *
     * @param address 地址
     * @return 类别
     */
    public static AddressClass classify(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (METADATA.stream().anyMatch(c -> c.contains(bytes))) {
            return AddressClass.METADATA;
        }
        if (address instanceof Inet4Address) {
            return match(RANGES_V4, bytes);
        }
        if (MAPPED.contains(bytes) || NAT64.contains(bytes)) {
            return classify(v4(bytes, 12));
        }
        if (SIX_TO_FOUR.contains(bytes)) {
            return classify(v4(bytes, 2));
        }
        AddressClass v6 = match(RANGES_V6, bytes);
        if (v6 != AddressClass.PUBLIC) {
            return v6;
        }
        if (COMPATIBLE.contains(bytes)) {
            return classify(v4(bytes, 12));
        }
        return AddressClass.PUBLIC;
    }

    /**
     * {@code web_fetch} 的判定：只放行公网地址。
     *
     * @param address 地址
     * @return 不是公网地址时为 true
     */
    public static boolean isBlocked(InetAddress address) {
        return classify(address) != AddressClass.PUBLIC;
    }

    /**
     * 把 URL 中的主机名按 IP 字面量解析。
     *
     * @param host URL 的主机部分（IPv6 可以带方括号）
     * @return IP 字面量对应的地址；是普通主机名时为空
     * @throws IllegalArgumentException 主机名只由数字与点组成却不是合法的 IPv4，或方括号中不是合法的 IPv6 时
     */
    public static Optional<InetAddress> literal(String host) {
        String value = host.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.contains(":")) {
            return Optional.of(ipv6(value));
        }
        if (!NUMERIC_HOST.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(ipv4(value.endsWith(".") ? value.substring(0, value.length() - 1) : value));
    }

    /**
     * 判断地址是否落在一个 CIDR 段中，供 {@link EndpointPolicy} 匹配配置的私有网段。
     *
     * @param cidr 段，例如 {@code 10.0.0.0/8}、{@code fc00::/7}
     * @param address 地址
     * @return 落在段中时为 true；地址族不同时为 false
     * @throws IllegalArgumentException 段的写法不合法时
     */
    static boolean contains(String cidr, InetAddress address) {
        return Cidr.of(cidr).contains(address.getAddress());
    }

    private static AddressClass match(List<Range> ranges, byte[] bytes) {
        for (Range range : ranges) {
            if (range.cidr().contains(bytes)) {
                return range.type();
            }
        }
        return AddressClass.PUBLIC;
    }

    /** {@code inet_aton} 语义：1 到 4 段，最后一段填满剩余字节；每段可以是十进制、八进制（前导 0）或十六进制（0x）。 */
    private static InetAddress ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length > 4) {
            throw new IllegalArgumentException("不是合法的 IPv4 地址：" + value);
        }
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = part(parts[i], value);
        }
        long result = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            if (numbers[i] > 0xFF) {
                throw new IllegalArgumentException("不是合法的 IPv4 地址：" + value);
            }
            result = (result << 8) | numbers[i];
        }
        int remaining = 4 - (parts.length - 1);
        long last = numbers[parts.length - 1];
        if (last >= (1L << (8 * remaining))) {
            throw new IllegalArgumentException("不是合法的 IPv4 地址：" + value);
        }
        result = (result << (8 * remaining)) | last;
        byte[] bytes = {(byte) (result >>> 24), (byte) (result >>> 16), (byte) (result >>> 8), (byte) result};
        return address(bytes, value);
    }

    private static long part(String part, String value) {
        try {
            if (part.startsWith("0x")) {
                return part.length() == 2 ? 0 : Long.parseLong(part.substring(2), 16);
            }
            if (part.length() > 1 && part.startsWith("0")) {
                return Long.parseLong(part.substring(1), 8);
            }
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法的 IPv4 地址：" + value, e);
        }
    }

    private static InetAddress ipv6(String value) {
        try {
            // 冒号使 JDK 按字面量解析，不做 DNS 查询
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("不是合法的 IPv6 地址：" + value, e);
        }
    }

    private static InetAddress v4(byte[] bytes, int offset) {
        byte[] v4 = {bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]};
        return address(v4, "");
    }

    private static InetAddress address(byte[] bytes, String value) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("不是合法的 IP 地址：" + value, e);
        }
    }

    /** 一个段与它的类别。 */
    private record Range(Cidr cidr, AddressClass type) {}

    /** CIDR 段。 */
    private record Cidr(byte[] network, int prefix) {

        static Cidr of(String cidr) {
            String[] parts = cidr.strip().split("/");
            if (parts.length != 2 || parts[0].isEmpty() || !parts[0].matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("不是合法的网段：" + cidr);
            }
            try {
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > network.length * 8) {
                    throw new IllegalArgumentException("网段的前缀长度不合法：" + cidr);
                }
                return new Cidr(network, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("不是合法的网段：" + cidr, e);
            }
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int bits = prefix % 8;
            if (bits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - bits)) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }
}
