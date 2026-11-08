package cn.refinex.meridian.runtime.egress.domain;

import java.util.Optional;

/**
 * 出网请求的目标：代理收到的 {@code :authority}（明文 HTTP 请求的主机，CONNECT 的 {@code host:port}）解析出的主机与端口。
 *
 * @param host 规整后的主机名或 IP 字面量（小写，IPv6 不带方括号）
 * @param port 端口，1 至 65535
 */
public record EgressTarget(String host, int port) {

    /** 明文 HTTP 请求的 {@code :authority} 不带端口时的默认端口。 */
    public static final int DEFAULT_HTTP_PORT = 80;

    /** 目标原文的长度上限：主机名 253 个字符加端口，超出的一律不合法。 */
    static final int MAX_AUTHORITY_LENGTH = 300;

    /**
     * 解析 {@code host}、{@code host:port}、{@code [ipv6]}、{@code [ipv6]:port}。
     *
     * @param authority 目标原文；可为空
     * @return 目标；为空、带用户信息、主机不合法或端口不合法时为空
     */
    public static Optional<EgressTarget> parse(String authority) {
        if (authority == null || authority.isEmpty() || authority.length() > MAX_AUTHORITY_LENGTH) {
            return Optional.empty();
        }
        String host;
        String port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            host = authority.substring(1, close);
            String rest = authority.substring(close + 1);
            if (rest.isEmpty()) {
                port = null;
            } else if (rest.startsWith(":")) {
                port = rest.substring(1);
            } else {
                return Optional.empty();
            }
            if (host.indexOf(':') < 0) {
                return Optional.empty();
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon < 0) {
                host = authority;
                port = null;
            } else if (authority.indexOf(':', colon + 1) >= 0) {
                // 不带方括号的 IPv6 不是合法的 authority
                return Optional.empty();
            } else {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
        }
        Optional<Integer> parsedPort = port == null ? Optional.of(DEFAULT_HTTP_PORT) : parsePort(port);
        if (parsedPort.isEmpty()) {
            return Optional.empty();
        }
        return HostNames.normalize(host).map(h -> new EgressTarget(h, parsedPort.get()));
    }

    private static Optional<Integer> parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            return Optional.empty();
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
            value = value * 10 + (c - '0');
        }
        return value >= 1 && value <= 65_535 ? Optional.of(value) : Optional.empty();
    }
}
