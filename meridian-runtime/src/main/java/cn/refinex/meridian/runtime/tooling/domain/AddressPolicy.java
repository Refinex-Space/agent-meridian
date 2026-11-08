package cn.refinex.meridian.runtime.tooling.domain;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code web_fetch} 的 SSRF 地址策略（架构设计「平台工具」：拒绝内网、回环、链路本地地址），纯函数。
 *
 * <p>两件事：
 *
 * <ul>
 *   <li>{@link #literal(String)}：把 URL 中的主机名按 IP 字面量解析，覆盖 {@code inet_aton} 接受的全部写法（十进制
 *       {@code 2130706433}、八进制 {@code 0177.0.0.1}、十六进制 {@code 0x7f.1}、省略段 {@code 127.1}）与 IPv6 字面量。HTTP 客户端
 *       与操作系统对这些写法的处理不一致，所以平台自己解析、自己判定，不交给 DNS；只由数字和点组成却解析不了的主机名直接拒绝。
 *   <li>{@link #isBlocked(InetAddress)}：IPv4 与 IPv6 的私有、共享（CGNAT，含阿里云元数据 {@code 100.100.100.200}）、回环、链路本地
 *       （含 {@code 169.254.169.254}）、未指定、文档、基准测试、保留、组播与广播段；IPv4 映射、IPv4 兼容、NAT64、6to4 地址按内嵌的
 *       IPv4 判定，Teredo 与本地 NAT64 一律拒绝（隧道地址可以指向任意内网）；IPv6 唯一本地地址（含 AWS 元数据 {@code fd00:ec2::254}）。
 * </ul>
 *
 * <p>判定在 DNS 解析之后、建立连接之前进行，连接用的就是被判定的地址，所以解析结果变化（DNS 重绑定）也绕不过去；每一跳重定向都重新判定。
 */
public final class AddressPolicy {

    /** 被拒绝的 IPv4 段。 */
    private static final List<Cidr> BLOCKED_V4 = List.of(
            Cidr.of("0.0.0.0/8"),
            Cidr.of("10.0.0.0/8"),
            Cidr.of("100.64.0.0/10"),
            Cidr.of("127.0.0.0/8"),
            Cidr.of("169.254.0.0/16"),
            Cidr.of("172.16.0.0/12"),
            Cidr.of("192.0.0.0/24"),
            Cidr.of("192.0.2.0/24"),
            Cidr.of("192.88.99.0/24"),
            Cidr.of("192.168.0.0/16"),
            Cidr.of("198.18.0.0/15"),
            Cidr.of("198.51.100.0/24"),
            Cidr.of("203.0.113.0/24"),
            Cidr.of("224.0.0.0/4"),
            Cidr.of("240.0.0.0/4"));

    /** 被拒绝的 IPv6 段（不含按内嵌 IPv4 判定的几种）。 */
    private static final List<Cidr> BLOCKED_V6 = List.of(
            Cidr.of("::/128"),
            Cidr.of("::1/128"),
            Cidr.of("64:ff9b:1::/48"),
            Cidr.of("100::/64"),
            Cidr.of("2001::/32"),
            Cidr.of("2001:db8::/32"),
            Cidr.of("fc00::/7"),
            Cidr.of("fe80::/10"),
            Cidr.of("fec0::/10"),
            Cidr.of("ff00::/8"));

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
     * 地址是否被拒绝。
     *
     * @param address 地址
     * @return 被拒绝时为 true
     */
    public static boolean isBlocked(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return BLOCKED_V4.stream().anyMatch(c -> c.contains(bytes));
        }
        if (MAPPED.contains(bytes) || NAT64.contains(bytes)) {
            return isBlocked(v4(bytes, 12));
        }
        if (SIX_TO_FOUR.contains(bytes)) {
            return isBlocked(v4(bytes, 2));
        }
        if (BLOCKED_V6.stream().anyMatch(c -> c.contains(bytes))) {
            return true;
        }
        if (COMPATIBLE.contains(bytes)) {
            return isBlocked(v4(bytes, 12));
        }
        return false;
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

    /** CIDR 段。 */
    private record Cidr(byte[] network, int prefix) {

        static Cidr of(String cidr) {
            String[] parts = cidr.split("/");
            try {
                return new Cidr(InetAddress.getByName(parts[0]).getAddress(), Integer.parseInt(parts[1]));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(cidr, e);
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
