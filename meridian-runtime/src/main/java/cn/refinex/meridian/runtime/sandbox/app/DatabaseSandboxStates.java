package cn.refinex.meridian.runtime.sandbox.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.port.SandboxStatePort;
import cn.refinex.meridian.runtime.egress.app.EgressRevocations;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxRecord;
import cn.refinex.meridian.runtime.sandbox.domain.SessionSandbox;
import cn.refinex.meridian.runtime.sandbox.infra.SessionSandboxRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * {@link SandboxStatePort} 的数据库实现（{@code rt.sessions} 的沙箱列），见架构设计「端口」「会话沙箱」。内核每轮经 {@link #find}
 * 读出 {@link SandboxService#prepare} 准备好的沙箱；保存、标记使用与清除供内核之外的调用方使用，沙箱的获取与回收走
 * {@link SandboxService}（它们要在同一把会话行锁里完成判定）。
 *
 * <p>保存与清除改动 Pod IP 时在同一事务里交给 {@link EgressRevocations}（D50 C-11）。会话、租户与项目从 Reactor Context 中的
 * {@code PlatformContext} 取。线程安全：无状态，数据库访问经 {@link Blocking}。
 */
@Service
public class DatabaseSandboxStates implements SandboxStatePort {

    private final SessionSandboxRepository sessions;
    private final TransactionTemplate transactions;
    private final EgressRevocations egress;
    private final Clock clock;

    /**
     * 创建端口实现。
     *
     * @param sessions 会话的沙箱列
     * @param transactions 事务模板
     * @param egress 出网授权的失效
     * @param clock 时钟
     */
    public DatabaseSandboxStates(
            SessionSandboxRepository sessions,
            TransactionTemplate transactions,
            EgressRevocations egress,
            Clock clock) {
        this.sessions = sessions;
        this.transactions = transactions;
        this.egress = egress;
        this.clock = clock;
    }

    @Override
    public Mono<Optional<SandboxHandle>> find(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return Blocking.call(() -> sessions.find(sessionId).flatMap(SessionSandbox::handle));
    }

    /** 保存时沿用会话原有沙箱的规格；会话还没有沙箱时以 {@link IllegalStateException} 失败（规格只有获取时才知道）。 */
    @Override
    public Mono<Void> save(String sessionId, SandboxHandle handle) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(handle, "handle");
        return Blocking.run(() -> transactions.executeWithoutResult(tx -> {
            SessionSandbox row = sessions.lock(sessionId, false)
                    .filter(r -> r.sandbox() != null)
                    .orElseThrow(() -> new IllegalStateException("会话还没有沙箱，不能只保存句柄：" + sessionId));
            sessions.saveSandbox(
                    sessionId, SandboxRecord.of(handle, row.sandbox().size()), handle.podIp(), clock.instant(), null);
            egress.podIpChanged(row.podIp(), handle.podIp());
        }));
    }

    @Override
    public Mono<Void> markUsed(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return Blocking.run(() -> sessions.markUsed(sessionId, clock.instant()));
    }

    /** 清除沙箱与 Pod IP，保留快照键。 */
    @Override
    public Mono<Void> clear(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return Blocking.run(() -> transactions.executeWithoutResult(tx -> sessions.lock(sessionId, false)
                .ifPresent(row -> {
                    sessions.clearSandbox(sessionId, row.snapshotKey(), clock.instant());
                    egress.podIpChanged(row.podIp(), null);
                })));
    }
}
