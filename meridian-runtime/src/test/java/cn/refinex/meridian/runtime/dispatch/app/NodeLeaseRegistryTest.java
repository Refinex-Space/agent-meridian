package cn.refinex.meridian.runtime.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.port.LeaseRegistry.Lease;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.LeaseLoss;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link NodeLeaseRegistry} 与 {@link LeaseHandle}：同一线程一个条目、子线程借用父租约、权限安装标记、信号补发。 */
class NodeLeaseRegistryTest {

    private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");

    private final NodeLeaseRegistry registry = new NodeLeaseRegistry();

    @Test
    void reservedEntryIsHeldButNotUsableUntilActivated() {
        LeaseHandle handle = registry.reserve("thr_1").orElseThrow();

        assertThat(registry.heldThreadIds()).containsExactly("thr_1");
        assertThat(registry.find("thr_1")).isEmpty();
        assertThat(registry.activeHandles()).isEmpty();

        handle.activate(claimed("trn_1", "thr_1", 3, null));

        assertThat(registry.find("thr_1")).contains(new Lease("thr_1", "trn_1", 3, false));
        assertThat(registry.activeHandles()).containsExactly(handle);
    }

    @Test
    void aThreadHasAtMostOneEntryUntilReleased() {
        LeaseHandle first = registry.reserve("thr_1").orElseThrow();
        first.activate(claimed("trn_1", "thr_1", 1, null));

        assertThat(registry.reserve("thr_1")).isEmpty();

        first.release();

        assertThat(registry.heldThreadIds()).isEmpty();
        assertThat(registry.find("thr_1")).isEmpty();
        assertThat(registry.reserve("thr_1")).isPresent();
    }

    @Test
    void cancelledReservationFreesTheThread() {
        LeaseHandle handle = registry.reserve("thr_1").orElseThrow();

        registry.cancel(handle);

        assertThat(registry.heldThreadIds()).isEmpty();
        assertThat(handle.isReleased()).isTrue();
    }

    @Test
    void releasingAnOldHandleDoesNotRemoveANewerOne() {
        LeaseHandle old = registry.reserve("thr_1").orElseThrow();
        old.activate(claimed("trn_1", "thr_1", 1, null));
        old.release();
        LeaseHandle current = registry.reserve("thr_1").orElseThrow();
        current.activate(claimed("trn_1", "thr_1", 2, null));

        old.release();

        assertThat(registry.find("thr_1")).contains(new Lease("thr_1", "trn_1", 2, false));
    }

    @Test
    void childThreadsUseTheParentLeaseAndAreDetachedOnRelease() {
        LeaseHandle parent = registry.reserve("thr_main").orElseThrow();
        parent.activate(claimed("trn_1", "thr_main", 5, null));

        registry.attachChildThread("thr_child", "thr_main");

        assertThat(registry.find("thr_child")).contains(new Lease("thr_main", "trn_1", 5, false));
        registry.detachChildThread("thr_child");
        assertThat(registry.find("thr_child")).isEmpty();

        registry.attachChildThread("thr_child", "thr_main");
        parent.release();
        assertThat(registry.find("thr_child")).isEmpty();
    }

    @Test
    void attachingToAThreadNotExecutingHereFails() {
        assertThatThrownBy(() -> registry.attachChildThread("thr_child", "thr_missing"))
                .isInstanceOf(IllegalStateException.class);
        registry.reserve("thr_reserved");
        assertThatThrownBy(() -> registry.attachChildThread("thr_child", "thr_reserved"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void permissionInstallFlagIsPerWrittenThread() {
        LeaseHandle parent = registry.reserve("thr_main").orElseThrow();
        parent.activate(claimed("trn_1", "thr_main", 2, null));
        registry.attachChildThread("thr_child", "thr_main");

        registry.markPermissionInstall("thr_child", true);

        assertThat(registry.find("thr_child").orElseThrow().permissionInstall()).isTrue();
        assertThat(registry.find("thr_main").orElseThrow().permissionInstall()).isFalse();

        registry.markPermissionInstall("thr_child", false);
        registry.markPermissionInstall("thr_main", true);

        assertThat(registry.find("thr_child").orElseThrow().permissionInstall()).isFalse();
        assertThat(registry.find("thr_main").orElseThrow().permissionInstall()).isTrue();
    }

    @Test
    void lostLeaseIsNotFoundButStillHeld() {
        LeaseHandle handle = registry.reserve("thr_1").orElseThrow();
        handle.activate(claimed("trn_1", "thr_1", 1, null));

        handle.lost(LeaseLoss.REVOKED);

        assertThat(registry.find("thr_1")).isEmpty();
        assertThat(registry.heldThreadIds()).containsExactly("thr_1");
        assertThat(handle.loss()).contains(LeaseLoss.REVOKED);
    }

    @Test
    void signalsBeforeListeningAreReplayedOnceAndLaterSignalsDeliveredOnce() {
        LeaseHandle handle = registry.reserve("thr_1").orElseThrow();
        Instant interruptAt = NOW.minusSeconds(1);
        handle.activate(claimed("trn_1", "thr_1", 1, interruptAt));
        handle.interruptRequested(interruptAt.plusSeconds(5));
        List<String> received = new ArrayList<>();

        handle.listen(new LeaseListener() {
            @Override
            public void onInterruptRequested(Instant requestedAt) {
                received.add("interrupt@" + requestedAt);
            }

            @Override
            public void onLeaseLost(LeaseLoss loss) {
                received.add("lost:" + loss);
            }
        });
        handle.interruptRequested(interruptAt.plusSeconds(10));
        handle.lost(LeaseLoss.RENEWAL_TIMEOUT);
        handle.lost(LeaseLoss.REVOKED);

        assertThat(received).containsExactly("interrupt@" + interruptAt, "lost:RENEWAL_TIMEOUT");
        assertThatThrownBy(() -> handle.listen(new LeaseListener() {
                    @Override
                    public void onInterruptRequested(Instant requestedAt) {}

                    @Override
                    public void onLeaseLost(LeaseLoss loss) {}
                }))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ClaimedTurn claimed(String turnId, String threadId, long epoch, Instant interruptRequestedAt) {
        return new ClaimedTurn(
                turnId,
                "ten_1",
                "prj_1",
                "ses_1",
                threadId,
                "messages",
                epoch,
                1,
                0,
                interruptRequestedAt,
                NOW,
                NOW,
                NOW.plusSeconds(30));
    }
}
