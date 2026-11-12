package cn.refinex.meridian.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 供应商端点的地址策略（T15a，ADR-0045 第 5 条）：协议、主机写法、各类地址与可配置的私有网段。 */
class EndpointPolicyTest {

    /** 测试用解析器：只认识表中的主机名。 */
    private static final EndpointPolicy.Resolver DNS = host -> {
        Map<String, List<String>> table = Map.of(
                "api.example.com", List.of("93.184.216.34"),
                "vllm.corp.internal", List.of("10.20.0.5"),
                "gpu.corp.internal", List.of("192.168.8.9"),
                "mixed.example.com", List.of("93.184.216.34", "169.254.169.254"),
                "localhost", List.of("127.0.0.1"));
        List<String> addresses = table.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        return addresses.stream()
                .map(a -> {
                    try {
                        return InetAddress.getByName(a);
                    } catch (UnknownHostException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    };

    @ParameterizedTest(name = "默认策略放行 {0}")
    @ValueSource(
            strings = {
                "https://api.example.com/v1",
                "http://vllm.corp.internal:8000/v1",
                "https://gpu.corp.internal/v1",
                "http://10.0.0.8:8000/v1",
                "https://[2606:4700:4700::1111]/v1",
                "http://172.16.0.1/v1"
            })
    void defaultPolicyAllowsPublicAndPrivateNetworks(String url) {
        assertThat(EndpointPolicy.DEFAULT.violation(url, DNS)).isEmpty();
    }

    @ParameterizedTest(name = "默认策略拒绝 {0}")
    @ValueSource(
            strings = {
                "ftp://api.example.com/v1",
                "file:///etc/passwd",
                "api.example.com/v1",
                "https://user:pass@api.example.com/v1",
                "http://localhost:18090/v1",
                "http://127.1:18090/v1",
                "http://2130706433/v1",
                "http://169.254.169.254/latest",
                "http://100.100.100.200/latest",
                "http://[fd00:ec2::254]/latest",
                "http://[::ffff:169.254.169.254]/",
                "http://0.0.0.0/",
                "http://224.0.0.1/",
                "https://mixed.example.com/v1",
                "https://unknown.example.com/v1",
                "http://08.0.0.1/",
                "http:///v1",
                ""
            })
    void defaultPolicyRejectsDangerousEndpoints(String url) {
        assertThat(EndpointPolicy.DEFAULT.violation(url, DNS)).isPresent();
    }

    @Test
    void loopbackIsAllowedOnlyWhenConfigured() {
        EndpointPolicy dev = new EndpointPolicy(true, EndpointPolicy.DEFAULT_PRIVATE_NETWORKS);
        assertThat(dev.violation("http://localhost:18090/v1", DNS)).isEmpty();
        assertThat(dev.violation("http://[::1]:18090/v1", DNS)).isEmpty();
        assertThat(dev.violation("http://169.254.169.254/", DNS)).isPresent();
    }

    @Test
    void privateNetworksCanBeNarrowed() {
        EndpointPolicy narrowed = new EndpointPolicy(false, List.of("10.20.0.0/16"));
        assertThat(narrowed.violation("http://vllm.corp.internal:8000/v1", DNS)).isEmpty();
        assertThat(narrowed.violation("https://gpu.corp.internal/v1", DNS))
                .hasValueSatisfying(reason -> assertThat(reason).contains("未放行的内网地址"));
        assertThat(new EndpointPolicy(false, List.of()).violation("http://10.20.0.5/v1", DNS))
                .isPresent();
    }

    @Test
    void reservedRangesAreAllowedOnlyWhenListedButMetadataNeverIs() {
        // fake-ip 方式的透明代理把公网域名解析到 198.18.0.0/15
        EndpointPolicy.Resolver fakeIp = host -> List.of(InetAddress.getByName("198.18.0.106"));
        assertThat(EndpointPolicy.DEFAULT.violation("https://dashscope.aliyuncs.com/compatible-mode/v1", fakeIp))
                .hasValueSatisfying(reason -> assertThat(reason).contains("未放行的保留地址"));
        EndpointPolicy withFakeIp = new EndpointPolicy(false, List.of("10.0.0.0/8", "198.18.0.0/15"));
        assertThat(withFakeIp.violation("https://dashscope.aliyuncs.com/compatible-mode/v1", fakeIp))
                .isEmpty();
        // 列出链路本地段也不放行元数据与链路本地地址
        EndpointPolicy careless = new EndpointPolicy(false, List.of("169.254.0.0/16", "0.0.0.0/0"));
        assertThat(careless.violation("http://169.254.169.254/latest", DNS)).isPresent();
        assertThat(careless.violation("http://169.254.1.1/", DNS)).isPresent();
        assertThat(careless.violation("http://224.0.0.1/", DNS)).isPresent();
    }

    @Test
    void rejectsMalformedNetworks() {
        assertThatThrownBy(() -> new EndpointPolicy(false, List.of("10.0.0.0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointPolicy(false, List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointPolicy(false, List.of("example.com/8")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
