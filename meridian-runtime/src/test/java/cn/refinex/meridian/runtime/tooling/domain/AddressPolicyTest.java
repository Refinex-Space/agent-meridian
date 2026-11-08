package cn.refinex.meridian.runtime.tooling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SSRF 地址策略的表驱动测试（T10b-A01）：IP 分类与各种 IP 写法。 */
class AddressPolicyTest {

    @ParameterizedTest(name = "{0} 被拒绝：{1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // IPv4 私有、共享、回环、链路本地、元数据
                "10.0.0.1            | 私有 10/8",
                "172.16.5.4          | 私有 172.16/12",
                "172.31.255.255      | 私有 172.16/12 上界",
                "192.168.1.10        | 私有 192.168/16",
                "127.0.0.1           | 回环",
                "127.255.255.254     | 回环 127/8",
                "169.254.169.254     | 云元数据（链路本地）",
                "169.254.0.1         | 链路本地",
                "100.64.0.1          | CGNAT 共享地址",
                "100.100.100.200     | 阿里云元数据（CGNAT 段）",
                "0.0.0.0             | 未指定",
                "0.1.2.3             | 本网络 0/8",
                "192.0.0.8           | IETF 协议分配",
                "192.0.2.1           | 文档 TEST-NET-1",
                "198.18.0.1          | 基准测试",
                "224.0.0.1           | 组播",
                "255.255.255.255     | 广播",
                "240.0.0.1           | 保留",
                // IPv6
                "::1                 | IPv6 回环",
                "::                  | IPv6 未指定",
                "fe80::1             | IPv6 链路本地",
                "fc00::1             | IPv6 唯一本地",
                "fd00:ec2::254       | AWS IPv6 元数据（唯一本地）",
                "fec0::1             | IPv6 站点本地（废弃）",
                "ff02::1             | IPv6 组播",
                "::ffff:127.0.0.1    | IPv4 映射的回环",
                "::ffff:169.254.169.254 | IPv4 映射的元数据",
                "::10.0.0.1          | IPv4 兼容的私有地址",
                "64:ff9b::a00:1      | NAT64 内嵌 10.0.0.1",
                "64:ff9b:1::1        | 本地 NAT64",
                "2002:a00:1::1       | 6to4 内嵌 10.0.0.1",
                "2001:0:4136:e378::1 | Teredo 隧道",
                "2001:db8::1         | IPv6 文档"
            })
    void blockedAddresses(String address, String reason) throws Exception {
        assertThat(AddressPolicy.isBlocked(InetAddress.getByName(address.strip())))
                .as(reason)
                .isTrue();
    }

    @ParameterizedTest(name = "{0} 放行")
    @ValueSource(
            strings = {
                "8.8.8.8",
                "1.1.1.1",
                "172.15.255.255",
                "172.32.0.1",
                "100.63.255.255",
                "100.128.0.1",
                "169.253.255.255",
                "2606:4700:4700::1111",
                "2002:808:808::1",
                "64:ff9b::808:808"
            })
    void publicAddresses(String address) throws Exception {
        assertThat(AddressPolicy.isBlocked(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest(name = "{0} 解析为 {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "2130706433       | 127.0.0.1",
                "0177.0.0.1       | 127.0.0.1",
                "0x7f.0.0.1       | 127.0.0.1",
                "0x7f000001       | 127.0.0.1",
                "127.1            | 127.0.0.1",
                "0177.1           | 127.0.0.1",
                "2852039166       | 169.254.169.254",
                "0251.0376.0251.0376 | 169.254.169.254",
                "0xa9.0xfe.0xa9.0xfe | 169.254.169.254",
                "169.254.43518    | 169.254.169.254",
                "012.0.0.1        | 10.0.0.1",
                "3232235777       | 192.168.1.1",
                "127.0.0.1.       | 127.0.0.1",
                "[::1]            | 0:0:0:0:0:0:0:1",
                "[::ffff:7f00:1]  | 127.0.0.1"
            })
    void numericHostFormsAreParsedAsLiteralsAndBlocked(String host, String expected) {
        InetAddress address = AddressPolicy.literal(host.strip()).orElseThrow();
        assertThat(address.getHostAddress()).isEqualTo(expected.strip());
        assertThat(AddressPolicy.isBlocked(address)).isTrue();
    }

    @ParameterizedTest(name = "{0} 是主机名")
    @ValueSource(strings = {"example.com", "docs.example.com", "cafe.be", "0x10.com", "1password.com", "localhost"})
    void hostNamesAreNotLiterals(String host) {
        assertThat(AddressPolicy.literal(host)).isEmpty();
    }

    @ParameterizedTest(name = "{0} 不合法")
    @ValueSource(strings = {"256.0.0.1", "1.2.3.4.5", "08.0.0.1", "4294967296", "1.2.65536", "[::g]"})
    void ambiguousNumericHostsAreRejected(String host) {
        assertThatThrownBy(() -> AddressPolicy.literal(host)).isInstanceOf(IllegalArgumentException.class);
    }
}
