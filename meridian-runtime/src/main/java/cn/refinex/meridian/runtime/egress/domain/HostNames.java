package cn.refinex.meridian.runtime.egress.domain;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;

/**
 * 主机名的规整与校验：白名单条目与代理请求的目标用同一套规则，二者都规整后再逐字比较。
 *
 * <p>只接受 DNS 名（字母、数字、{@code -}、{@code _}，标签 1 至 63 个字符，总长不超过 253）与 IPv4、IPv6 字面量；大写转小写，去掉末尾的一个
 * {@code .}。其他写法（百分号编码、空白、控制字符、{@code @}）一律不合法，这样目标里无论塞进什么，都只可能与一条规整后的白名单条目逐字相等。
 * 十进制整数、十六进制等非常规的 IPv4 写法按 DNS 名处理，不会与任何正常的白名单条目相等，因而被拒绝。
 */
final class HostNames {

    /** DNS 名的总长上限（RFC 1035）。 */
    static final int MAX_LENGTH = 253;

    /** 单个标签的长度上限（RFC 1035）。 */
    private static final int MAX_LABEL = 63;

    /** IPv6 文本形式的长度上限（带内嵌 IPv4 的最长写法）。 */
    private static final int MAX_IPV6_LENGTH = 45;

    private HostNames() {}

    /**
     * 规整一个主机名或 IP 字面量。
     *
     * @param raw 原文，IPv6 字面量不带方括号
     * @return 规整后的主机名；不合法时为空
     */
    static Optional<String> normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        String host = raw.toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0) {
            return isIpv6(host) ? Optional.of(host) : Optional.empty();
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return isDnsName(host) ? Optional.of(host) : Optional.empty();
    }

    private static boolean isDnsName(String host) {
        if (host.isEmpty() || host.length() > MAX_LENGTH) {
            return false;
        }
        int labelLength = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                if (labelLength == 0) {
                    return false;
                }
                labelLength = 0;
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                if (++labelLength > MAX_LABEL) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return labelLength > 0;
    }

    private static boolean isIpv6(String host) {
        if (host.length() > MAX_IPV6_LENGTH) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == ':' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        try {
            return InetAddress.ofLiteral(host) instanceof Inet6Address;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
