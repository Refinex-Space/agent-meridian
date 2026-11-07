package cn.refinex.meridian.runtime.session.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.session.domain.SessionThread;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** 会话与线程的项目级仓储：只读写当前 {@code PlatformContext} 所在项目的数据（架构设计「租户隔离」）。 */
class SessionRepositoryIT {

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private final SessionRepository sessions = new SessionRepository(RuntimeTestDatabase.dataSource());
    private final ThreadRepository threads = new ThreadRepository(RuntimeTestDatabase.dataSource());

    @Test
    void sessionIsVisibleOnlyInsideItsProject() {
        TestSession session = TestSessions.create(clock);

        Session found = PlatformContexts.call(session.context(), () -> sessions.find(session.sessionId()))
                .orElseThrow();
        assertThat(found.tenantId()).isEqualTo(session.tenantId());
        assertThat(found.projectId()).isEqualTo(session.projectId());
        assertThat(found.interaction()).isEqualTo(Interaction.ATTENDED);
        assertThat(found.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(found.lastSeq()).isZero();
        assertThat(found.createdAt()).isEqualTo(clock.instant());

        PlatformContext otherProject = PlatformContext.builder()
                .tenantId(session.tenantId())
                .projectId(Ids.newId(IdPrefix.PROJECT))
                .build();
        PlatformContext otherTenant = PlatformContext.builder()
                .tenantId(Ids.newId(IdPrefix.TENANT))
                .projectId(session.projectId())
                .build();
        assertThat(PlatformContexts.call(otherProject, () -> sessions.find(session.sessionId())))
                .isEmpty();
        assertThat(PlatformContexts.call(otherTenant, () -> sessions.find(session.sessionId())))
                .isEmpty();
        assertThat(PlatformContexts.call(otherProject, () -> threads.find(session.mainThreadId())))
                .isEmpty();
    }

    @Test
    void threadCannotBeAddedToASessionOfAnotherProject() {
        TestSession session = TestSessions.create(clock);
        PlatformContext otherProject = PlatformContext.builder()
                .tenantId(session.tenantId())
                .projectId(Ids.newId(IdPrefix.PROJECT))
                .build();
        String intruder = Ids.newId(IdPrefix.THREAD);

        boolean inserted = PlatformContexts.call(
                otherProject,
                () -> threads.insert(
                        new NewThread(intruder, session.sessionId(), session.mainThreadId(), "toolu_x", "intruder"),
                        clock.instant()));

        assertThat(inserted).isFalse();
        assertThat(PlatformContexts.call(session.context(), () -> threads.find(intruder)))
                .isEmpty();
    }

    @Test
    void threadsAreListedMainFirstAndCarryTheirParent() {
        TestSession session = TestSessions.create(clock);
        String child = Ids.newId(IdPrefix.THREAD);
        PlatformContexts.run(
                session.context(),
                () -> threads.insert(
                        new NewThread(child, session.sessionId(), session.mainThreadId(), "toolu_1", "researcher"),
                        clock.instant()));

        var listed = PlatformContexts.call(session.context(), () -> threads.listBySession(session.sessionId()));

        assertThat(listed).extracting(SessionThread::id).containsExactly(session.mainThreadId(), child);
        assertThat(listed.get(0).isMain()).isTrue();
        assertThat(listed.get(1).parentThreadId()).isEqualTo(session.mainThreadId());
        assertThat(listed.get(1).parentToolUseId()).isEqualTo("toolu_1");
    }

    @Test
    void repositoryRefusesToRunWithoutPlatformContext() {
        assertThatThrownBy(() -> sessions.find("ses_any")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reactiveCallersGoThroughBlockingWithTheReactorContext() {
        TestSession session = TestSessions.create(clock);

        StepVerifier.create(Mono.just(session.sessionId())
                        .publishOn(Schedulers.parallel())
                        .flatMap(id -> Blocking.call(() -> sessions.find(id)))
                        .map(Optional::orElseThrow)
                        .map(Session::id)
                        .contextWrite(PlatformContexts.write(session.context())))
                .expectNext(session.sessionId())
                .verifyComplete();
    }
}
