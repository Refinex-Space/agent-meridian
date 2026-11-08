package cn.refinex.meridian.runtime.egress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 出网白名单的匹配：精确条目、{@code *.} 子域名条目、大小写与末尾点，以及不合法条目被忽略。 */
class EgressAllowlistTest {

    final EgressAllowlist allowlist = EgressAllowlist.of(List.of("PyPI.org", "*.Example.COM.", "10.0.0.5", "::1"));

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "pypi.org, true",
        "files.pypi.org, false",
        "a.example.com, true",
        "a.b.example.com, true",
        "example.com, false",
        "badexample.com, false",
        "example.com.evil.org, false",
        "10.0.0.5, true",
        "10.0.0.6, false",
        "::1, true",
    })
    void matchesExactAndWildcardEntries(String host, boolean permitted) {
        assertThat(allowlist.permits(host)).isEqualTo(permitted);
    }

    @Test
    void emptyAllowlistPermitsNothing() {
        assertThat(EgressAllowlist.of(List.of()).permits("pypi.org")).isFalse();
        assertThat(EgressAllowlist.of(null).permits("pypi.org")).isFalse();
    }

    @Test
    void ignoresMalformedEntries() {
        List<String> entries = Arrays.asList(
                "*", "*.", "a.*.example.com", "pypi.org:443", "https://pypi.org", "pypi.org/simple", "", null, "*.::1");
        EgressAllowlist bad = EgressAllowlist.of(entries);

        assertThat(bad.ignored()).containsExactlyElementsOf(entries);
        assertThat(bad.permits("pypi.org")).isFalse();
        assertThat(bad.permits("a.b.example.com")).isFalse();
    }

    @Test
    void targetsAreMatchedAfterNormalization() {
        EgressTarget target = EgressTarget.parse("API.Example.com.:443").orElseThrow();

        assertThat(allowlist.permits(target.host())).isTrue();
    }
}
