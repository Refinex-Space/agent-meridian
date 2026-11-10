package cn.refinex.meridian.runtime.egress.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * 出网代理的授权回调 {@code POST /internal/v1/egress:authorize} 经 HTTP 的集成测试（T12b）：放行、三种拒绝的 403 problem+json、
 * 请求不合法与缺少服务凭据，每次交互按 {@code meridian-internal-v1.yaml} 校验。会话的环境取开发配置中的 {@code env_dev_sandbox}
 * （白名单只有 {@code allowed-echo.meridian-egress-test.svc.cluster.local}）；每个用例用不同的 Pod IP，不清空共享的数据库。
 */
class EgressApiIT extends ApiTestBase {

    static final String PATH = "/internal/v1/egress:authorize";
    static final String ALLOWED = "allowed-echo.meridian-egress-test.svc.cluster.local";

    static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10.250." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    static TestSession sandboxSession(String podIp) {
        TestSession session = TestSessions.create(Clock.systemUTC(), "agv_dev_assistant_1", "env_dev_sandbox", 1);
        DbAssertions.update("UPDATE rt.sessions SET sandbox_pod_ip = ? WHERE id = ?", podIp, session.sessionId());
        return session;
    }

    static String body(String sourceIp, String target) {
        return "{\"source_ip\": \"" + sourceIp + "\", \"target\": \"" + target + "\"}";
    }

    @Test
    void allowlistedTargetsAreAllowedWithTheSessionOfTheSourceIp() {
        String ip = randomIp();
        TestSession session = sandboxSession(ip);

        InternalResponse http = internal(PATH, body(ip, ALLOWED), ApiTestKeys.egressProxyToken());
        InternalResponse connect = internal(PATH, body(ip, ALLOWED + ":443"), ApiTestKeys.egressProxyToken());

        assertThat(http.status()).isEqualTo(200);
        assertThat(http.body().get("session_id").asString()).isEqualTo(session.sessionId());
        assertThat(connect.status()).isEqualTo(200);
    }

    @Test
    void deniedRequestsAreProblemsWithTheReasonAsCode() {
        String ip = randomIp();
        sandboxSession(ip);

        InternalResponse notAllowlisted = internal(PATH, body(ip, "pypi.org:443"), ApiTestKeys.egressProxyToken());
        InternalResponse unknownSource = internal(PATH, body(randomIp(), ALLOWED), ApiTestKeys.egressProxyToken());
        InternalResponse invalidTarget = internal(PATH, body(ip, "user@" + ALLOWED), ApiTestKeys.egressProxyToken());

        assertThat(notAllowlisted.status()).isEqualTo(403);
        assertThat(notAllowlisted.body().get("code").asString()).isEqualTo("egress.not_allowlisted");
        assertThat(unknownSource.status()).isEqualTo(403);
        assertThat(unknownSource.body().get("code").asString()).isEqualTo("egress.unknown_source");
        assertThat(invalidTarget.status()).isEqualTo(403);
        assertThat(invalidTarget.body().get("code").asString()).isEqualTo("egress.invalid_target");
    }

    @Test
    void malformedRequestsAndMissingCredentialsAreRejected() {
        InternalResponse missingTarget =
                internal(PATH, "{\"source_ip\": \"10.250.0.1\"}", ApiTestKeys.egressProxyToken(), false);
        InternalResponse unauthenticated = internal(PATH, body("10.250.0.1", ALLOWED), null);
        InternalResponse apiKey = internal(PATH, body("10.250.0.1", ALLOWED), ApiTestKeys.KEY_A);

        assertThat(missingTarget.status()).isEqualTo(400);
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(apiKey.status()).as("业务系统的 API Key 不能调用内部接口").isEqualTo(401);
    }

    @Test
    void onlyTheEgressProxyMayAuthorizeEgress() {
        // 其他服务的有效令牌（ADR-0044：每个内部接口只接受列明的调用方）
        InternalResponse knowledge = internal(PATH, body("10.250.0.1", ALLOWED), ApiTestKeys.knowledgeToken());

        assertThat(knowledge.status()).isEqualTo(403);
        assertThat(knowledge.body().get("code").asString()).isEqualTo("access.forbidden");
    }
}
