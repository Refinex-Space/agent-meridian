package cn.refinex.meridian.runtime.egress.app;

import cn.refinex.meridian.runtime.egress.domain.EgressDecision;
import cn.refinex.meridian.runtime.egress.domain.EgressDecision.Reason;
import cn.refinex.meridian.runtime.egress.domain.EgressTarget;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 沙箱出网的授权判定（架构设计「沙箱出网」第 3 条）：出网代理对每个 HTTP 或 CONNECT 请求带上源 IP 与目标调用，按源 IP 找到会话与
 * 环境（{@link EgressGrants}，缓存 30 秒），按环境的白名单判定目标主机。任何一步不成立都拒绝。
 *
 * <p>每次判定计入 {@code meridian_egress_authorize_seconds}（标签 {@code result}、{@code reason}）。阻塞，在虚拟线程上调用。
 * 线程安全：无状态。
 */
@Service
public class EgressAuthorizer {

    /** 判定耗时的指标名，导出为 {@code meridian_egress_authorize_seconds}（T12b 任务要求）。 */
    public static final String AUTHORIZE_METRIC = "meridian.egress.authorize";

    /** 源 IP 原文的长度上限（IPv6 文本的最长写法）。 */
    private static final int MAX_IP_LENGTH = 45;

    private static final Logger log = LoggerFactory.getLogger(EgressAuthorizer.class);

    private final EgressGrants grants;
    private final MeterRegistry meters;

    /**
     * 创建判定服务。
     *
     * @param grants 按源 IP 的授权缓存
     * @param meters 指标注册表
     */
    public EgressAuthorizer(EgressGrants grants, MeterRegistry meters) {
        this.grants = grants;
        this.meters = meters;
    }

    /**
     * 判定一次出网请求。
     *
     * @param sourceIp 请求的源 IP（代理看到的 TCP 对端地址）
     * @param target 目标：明文 HTTP 请求的 {@code :authority}，CONNECT 的 {@code host:port}
     * @return 结论
     */
    public EgressDecision authorize(String sourceIp, String target) {
        Timer.Sample sample = Timer.start(meters);
        EgressDecision decision = decide(sourceIp, target);
        sample.stop(Timer.builder(AUTHORIZE_METRIC)
                .description("沙箱出网授权的判定耗时")
                .tag("result", decision.allowed() ? "allowed" : "denied")
                .tag("reason", decision.reason().value())
                .publishPercentileHistogram()
                .register(meters));
        if (!decision.allowed()) {
            log.debug(
                    "拒绝沙箱出网 sourceIp={} target={} reason={} sessionId={}",
                    sourceIp,
                    target,
                    decision.reason().value(),
                    decision.sessionId());
        }
        return decision;
    }

    private EgressDecision decide(String sourceIp, String rawTarget) {
        Optional<String> ip = normalizeIp(sourceIp);
        if (ip.isEmpty()) {
            return new EgressDecision(Reason.UNKNOWN_SOURCE, null, null);
        }
        Optional<EgressTarget> target = EgressTarget.parse(rawTarget);
        if (target.isEmpty()) {
            return new EgressDecision(Reason.INVALID_TARGET, null, null);
        }
        return grants.find(ip.get())
                .map(grant -> grant.decide(target.get()))
                .orElseGet(() -> new EgressDecision(Reason.UNKNOWN_SOURCE, null, target.get()));
    }

    /**
     * 校验源 IP 是 IP 字面量，转小写后原样返回：与 {@code rt.sessions.sandbox_pod_ip}（Kubernetes 给出的 Pod IP 文本）逐字比较，
     * 不改写成 JDK 的展开形式。
     */
    private static Optional<String> normalizeIp(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank() || sourceIp.length() > MAX_IP_LENGTH) {
            return Optional.empty();
        }
        String ip = sourceIp.strip().toLowerCase(Locale.ROOT);
        try {
            InetAddress.ofLiteral(ip);
            return Optional.of(ip);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
