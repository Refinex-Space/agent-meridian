package cn.refinex.meridian.runtime.egress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 出网目标的解析：代理转来的 {@code :authority} 的各种写法与非法输入。 */
class EgressTargetTest {

    @ParameterizedTest(name = "{0} → {1}:{2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "pypi.org                      | pypi.org         | 80",
                "pypi.org:443                  | pypi.org         | 443",
                "PyPI.Org.:443                 | pypi.org         | 443",
                "files.pythonhosted.org:8443   | files.pythonhosted.org | 8443",
                "my_host.internal:1            | my_host.internal | 1",
                "10.0.0.5:65535                | 10.0.0.5         | 65535",
                "[2001:DB8::1]:443             | 2001:db8::1      | 443",
                "[::1]                         | ::1              | 80",
            })
    void parsesHostAndPort(String authority, String host, int port) {
        assertThat(EgressTarget.parse(authority)).contains(new EgressTarget(host, port));
    }

    @ParameterizedTest(name = "非法：[{0}]")
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "pypi.org:0",
                "pypi.org:65536",
                "pypi.org:",
                "pypi.org:44a",
                "pypi.org:443:1",
                "user@pypi.org",
                "pypi.org/simple",
                "pypi.org ",
                "py pi.org",
                "pypi%2eorg",
                "pypi..org",
                ".pypi.org",
                "pypi.org..",
                "2001:db8::1",
                "[2001:db8::1",
                "[2001:db8::1]x",
                "[pypi.org]:443",
                "[::ffff:zz]:443",
                "evil.com\u0000.pypi.org",
                "évil.org",
            })
    void rejectsMalformed(String authority) {
        assertThat(EgressTarget.parse(authority)).isEmpty();
    }

    @Test
    void rejectsOverlongHostsAndLabels() {
        String label64 = "a".repeat(64);
        assertThat(EgressTarget.parse(label64 + ".example.com")).isEmpty();
        String host254 = ("a".repeat(62) + ".").repeat(4) + "ab";
        assertThat(host254).hasSize(254);
        assertThat(EgressTarget.parse(host254)).isEmpty();
        assertThat(EgressTarget.parse("x".repeat(EgressTarget.MAX_AUTHORITY_LENGTH + 1)))
                .isEmpty();
    }
}
