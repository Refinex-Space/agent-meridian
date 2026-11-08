package cn.refinex.meridian.runtime.sandbox.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 快照存储（{@code SnapshotStorePort} 的对象存储实现）：写入、流式读取、存在性，只接受快照前缀下的键。 */
class ObjectStoreSnapshotStoreIT {

    private final ObjectStoreSnapshotStore store = new ObjectStoreSnapshotStore(RuntimeTestObjectStore.get());

    @Test
    void snapshotsRoundTripAsStreams() throws Exception {
        String key = "rt/snapshots/ten_snap/prj_snap/ses_snap/01JX.tar";
        byte[] archive = new byte[3 * 1024 * 1024];
        new SecureRandom().nextBytes(archive);

        assertThat(store.exists(key)).isFalse();
        assertThat(store.open(key)).isEmpty();
        store.put(key, archive);

        assertThat(store.exists(key)).isTrue();
        Optional<InputStream> opened = store.open(key);
        assertThat(opened).isPresent();
        try (InputStream in = opened.get()) {
            assertThat(in.readAllBytes()).isEqualTo(archive);
        }
    }

    @Test
    void keysOutsideTheSnapshotPrefixAreRejected() {
        assertThatThrownBy(() -> store.put("rt/ten_x/ses_x/blobs/1", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.exists("rt/snapshots/../ten_x/blobs/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
