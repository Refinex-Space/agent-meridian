package cn.refinex.meridian.runtime.workspace.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort.Item;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code WorkspaceStorePort} 的数据库实现（架构设计「文件系统装配」「存储分工」）：版本号语义与 {@code putIfVersion}、64 KB
 * 以上进对象存储、被替换的对象被删除、按命名空间前缀分页、租户隔离。
 */
class DatabaseWorkspaceStoreIT {

    static final List<String> SESSION = List.of("ten_ws", "prj_ws", "_", "ses_ws");

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private DatabaseWorkspaceStore store;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        store = new DatabaseWorkspaceStore(
                new WorkspaceEntryRepository(RuntimeTestDatabase.dataSource()),
                RuntimeTestObjectStore.get(),
                RuntimeTestDatabase.transactions(),
                clock);
    }

    static Map<String, Object> file(String content) {
        return Map.of("content", content, "encoding", "utf-8");
    }

    @Test
    void concurrentFirstWritesToTheSameKeyAreBothRecorded() throws Exception {
        // R1 并发审查：两个写入都看到键不存在时，后插入的一方在冲突上什么都不做，put 曾不检查结果、静默丢掉本次写入
        int keys = 20;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.Future<?>> writes = new java.util.ArrayList<>();
            for (int k = 0; k < keys; k++) {
                String key = "/race" + k + ".md";
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                for (String text : List.of("甲", "乙")) {
                    writes.add(executor.submit(() -> {
                        start.await();
                        store.put(SESSION, key, file(text));
                        return null;
                    }));
                }
            }
            for (var write : writes) {
                write.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        for (int k = 0; k < keys; k++) {
            Item item = store.get(SESSION, "/race" + k + ".md").orElseThrow();
            assertThat(item.version()).as("两次写入都生效：一次创建、一次覆盖").isEqualTo(2);
        }
    }

    @Test
    void putIfVersionCreatesOnlyWhenAbsentAndUpdatesOnlyTheExpectedVersion() {
        assertThat(store.putIfVersion(SESSION, "/a.md", file("一"), 0)).isTrue();
        assertThat(store.putIfVersion(SESSION, "/a.md", file("重复创建"), 0))
                .as("0 表示期望不存在")
                .isFalse();
        assertThat(store.putIfVersion(SESSION, "/a.md", file("二"), 1)).isTrue();
        assertThat(store.putIfVersion(SESSION, "/a.md", file("过期的版本"), 1)).isFalse();
        store.put(SESSION, "/a.md", file("三"));

        Item item = store.get(SESSION, "/a.md").orElseThrow();
        assertThat(item.version()).isEqualTo(3);
        assertThat(item.value()).containsEntry("content", "三");
        assertThat(store.get(SESSION, "/missing.md")).isEmpty();
        assertThat(store.putIfVersion(SESSION, "/missing.md", file("x"), 5)).isFalse();
    }

    @Test
    void largeValuesGoToTheObjectStoreAndReplacedObjectsAreDeleted() {
        String big = "x".repeat(DatabaseWorkspaceStore.INLINE_LIMIT_BYTES + 1);
        store.put(SESSION, "/big.txt", file(big));
        String firstKey = objectKey("/big.txt");
        assertThat(firstKey).startsWith("rt/ten_ws/ses_ws/workspace/");
        assertThat(RuntimeTestObjectStore.get().get(firstKey)).isPresent();
        assertThat(store.get(SESSION, "/big.txt").orElseThrow().value()).containsEntry("content", big);

        assertThat(store.putIfVersion(SESSION, "/big.txt", file(big + "y"), 1)).isTrue();
        String secondKey = objectKey("/big.txt");
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(RuntimeTestObjectStore.get().get(firstKey)).as("被替换的对象已删除").isEmpty();

        assertThat(store.putIfVersion(SESSION, "/big.txt", file(big + "z"), 1))
                .as("版本不匹配")
                .isFalse();
        assertThat(objectKey("/big.txt")).isEqualTo(secondKey);

        store.put(SESSION, "/big.txt", file("变小了"));
        assertThat(objectKey("/big.txt")).as("变小后内联").isNull();
        assertThat(RuntimeTestObjectStore.get().get(secondKey)).isEmpty();

        store.put(SESSION, "/big2.txt", file(big));
        String thirdKey = objectKey("/big2.txt");
        store.delete(SESSION, "/big2.txt");
        assertThat(store.get(SESSION, "/big2.txt")).isEmpty();
        assertThat(RuntimeTestObjectStore.get().get(thirdKey)).as("删除条目时删除对象").isEmpty();
    }

    @Test
    void searchPagesThroughTheNamespacePrefixInKeyOrderWithoutCrossingSessionsOrTenants() {
        IntStream.range(0, 5).forEach(i -> store.put(SESSION, "/f" + i + ".md", file("文件 " + i)));
        store.put(List.of("ten_ws", "prj_ws", "_", "ses_ws", "nested"), "/n.md", file("子命名空间"));
        store.put(List.of("ten_ws", "prj_ws", "_", "ses_other"), "/f0.md", file("另一个会话"));
        store.put(List.of("ten_other", "prj_ws", "_", "ses_ws"), "/f0.md", file("另一个租户"));

        assertThat(store.search(SESSION, 3, 0)).extracting(Item::key).containsExactly("/f0.md", "/f1.md", "/f2.md");
        assertThat(store.search(SESSION, 3, 3)).extracting(Item::key).containsExactly("/f3.md", "/f4.md", "/n.md");
        assertThat(store.search(SESSION, 3, 6)).isEmpty();
        assertThat(store.search(SESSION, 10, 0))
                .as("同租户同会话，含子命名空间")
                .allSatisfy(item -> assertThat(item.namespace().subList(0, 4)).isEqualTo(SESSION));
        assertThat(DbAssertions.list(
                        "SELECT DISTINCT tenant_id || '/' || project_id FROM rt.workspace_entries ORDER BY 1",
                        String.class))
                .as("租户与项目列取自命名空间")
                .containsExactly("ten_other/prj_ws", "ten_ws/prj_ws");
    }

    @Test
    void namespacesMustStartWithTenantAndProject() {
        assertThatThrownBy(() -> store.get(List.of("ten_only"), "/a.md")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.search(SESSION, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String objectKey(String key) {
        return DbAssertions.list(
                        "SELECT object_key FROM rt.workspace_entries WHERE key = ? AND namespace = ?::text[]",
                        String.class,
                        key,
                        "{ten_ws,prj_ws,_,ses_ws}")
                .getFirst();
    }
}
