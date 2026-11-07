package cn.refinex.meridian.engine.agentscope.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentStateRepository;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryLeaseRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FencedAgentStateStore}（T04-A07）：租约有效时读写与版本号语义与 2.0.4 一致；租约失效时所有写入路径都抛
 * {@link LeaseLostException} 且不写；权限安装写入不更新 {@code content_epoch}；子线程用父轮次的租约。
 */
class FencedAgentStateStoreTest {

    private static final String USER = "prj_test:_";
    private static final String THREAD = "thr_main";
    private static final String CHILD = "thr_child";
    private static final String KEY = "agent_state";
    private static final long EPOCH = 3;

    private InMemoryLeaseRegistry leases;
    private InMemoryAgentStateRepository repository;
    private FencedAgentStateStore store;

    @BeforeEach
    void setUp() {
        leases = new InMemoryLeaseRegistry();
        repository = new InMemoryAgentStateRepository();
        store = new FencedAgentStateStore(repository, leases);
        leases.grant(THREAD, "trn_1", EPOCH);
        repository.activate(THREAD, EPOCH);
    }

    @Test
    void writesSucceedUnderAValidLeaseAndRoundTripThroughJson() {
        assertThat(store.supportsVersioning()).isTrue();
        assertThat(store.getVersioned(USER, THREAD, KEY, AgentState.class))
                .as("2.0.4 的约定：不存在的键报告版本 0")
                .isEqualTo(new VersionedState<AgentState>(null, 0L));

        store.save(USER, THREAD, KEY, state("你好"));

        VersionedState<AgentState> loaded = store.getVersioned(USER, THREAD, KEY, AgentState.class);
        assertThat(loaded.version()).isEqualTo(1);
        assertThat(loaded.value().getSessionId()).isEqualTo(THREAD);
        assertThat(loaded.value().getContext())
                .singleElement()
                .satisfies(m -> assertThat(m.getTextContent()).isEqualTo("你好"));
        assertThat(store.get(USER, THREAD, KEY, AgentState.class)).isPresent();
        assertThat(store.exists(USER, THREAD)).isTrue();
        assertThat(store.listSessionIds(USER)).containsExactly(THREAD);
    }

    @Test
    void saveIfVersionFollowsTheCompareAndSwapContractOf204() {
        assertThat(store.saveIfVersion(USER, THREAD, KEY, state("a"), 0))
                .as("期望 0：不存在才写")
                .isEqualTo(1);
        assertThat(store.saveIfVersion(USER, THREAD, KEY, state("b"), 0))
                .as("已存在时期望 0 冲突，不写")
                .isEqualTo(AgentStateStore.UNVERSIONED);
        assertThat(store.saveIfVersion(USER, THREAD, KEY, state("c"), 1)).isEqualTo(2);
        assertThat(store.saveIfVersion(USER, THREAD, KEY, state("d"), 1))
                .as("过期版本冲突，不写")
                .isEqualTo(AgentStateStore.UNVERSIONED);
        assertThat(store.saveIfVersion(USER, THREAD, KEY, state("e"), AgentStateStore.UNVERSIONED))
                .as("UNVERSIONED 是无条件写，返回本次写入的版本")
                .isEqualTo(3);
        assertThat(store.get(USER, THREAD, KEY, AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .getFirst()
                        .getTextContent())
                .isEqualTo("e");
    }

    @Test
    void listStatesAreStoredAsJsonArrays() {
        List<Msg> messages = List.of(new UserMessage("一"), new UserMessage("二"));

        store.save(USER, THREAD, "memory_messages", messages);

        assertThat(store.getList(USER, THREAD, "memory_messages", Msg.class))
                .extracting(Msg::getTextContent)
                .containsExactly("一", "二");
        assertThat(store.getList(USER, THREAD, "missing", Msg.class)).isEmpty();
    }

    @Test
    void everyWritePathFailsWithoutALeaseOnThisNode() {
        store.save(USER, THREAD, KEY, state("before"));
        int writes = repository.writeCount();
        leases.revoke(THREAD);

        assertAllWritesRejected();
        assertThat(repository.writeCount()).as("租约失效时什么都不写").isEqualTo(writes);
        assertThat(store.get(USER, THREAD, KEY, AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .getFirst()
                        .getTextContent())
                .isEqualTo("before");
    }

    @Test
    void everyWritePathFailsWhenTheDatabaseNoLongerConfirmsTheLease() {
        store.save(USER, THREAD, KEY, state("before"));
        int writes = repository.writeCount();
        // 本节点的 LeaseRegistry 仍有条目，但轮次已被回收或被其他节点以更高 epoch 认领
        repository.activate(THREAD, EPOCH + 1);

        assertAllWritesRejected();
        assertThat(repository.writeCount()).isEqualTo(writes);
    }

    @Test
    void permissionInstallWritesDoNotUpdateTheContentEpoch() {
        leases.markPermissionInstall(THREAD, true);
        store.save(USER, THREAD, KEY, state("权限"));
        leases.markPermissionInstall(THREAD, false);
        assertThat(repository.contentEpoch(USER, THREAD)).as("只有权限安装写入时没有内容写入").isEmpty();

        store.save(USER, THREAD, KEY, state("内容"));
        assertThat(repository.contentEpoch(USER, THREAD)).hasValue(EPOCH);

        // 下一次执行（新 epoch）开始时安装权限：content_epoch 仍是上一次内容写入的 epoch，崩溃恢复据此判断
        long next = EPOCH + 1;
        leases.grant(THREAD, "trn_2", next);
        repository.activate(THREAD, next);
        leases.markPermissionInstall(THREAD, true);
        store.saveIfVersion(USER, THREAD, KEY, state("新权限"), AgentStateStore.UNVERSIONED);
        leases.markPermissionInstall(THREAD, false);
        assertThat(repository.contentEpoch(USER, THREAD)).hasValue(EPOCH);

        store.saveIfVersion(USER, THREAD, KEY, state("新内容"), 3);
        assertThat(repository.contentEpoch(USER, THREAD)).hasValue(next);
    }

    @Test
    void childThreadsWriteUnderTheParentLease() {
        assertThatThrownBy(() -> store.save(USER, CHILD, KEY, state("未登记"))).isInstanceOf(LeaseLostException.class);

        leases.attachChildThread(CHILD, THREAD);
        store.save(USER, CHILD, KEY, state("子线程"));
        assertThat(store.get(USER, CHILD, KEY, AgentState.class)).isPresent();
        assertThat(repository.contentEpoch(USER, CHILD)).hasValue(EPOCH);

        repository.deactivate(THREAD);
        assertThatThrownBy(() -> store.save(USER, CHILD, KEY, state("父租约失效"))).isInstanceOf(LeaseLostException.class);

        leases.detachChildThread(CHILD);
        repository.activate(THREAD, EPOCH);
        assertThatThrownBy(() -> store.save(USER, CHILD, KEY, state("已注销"))).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void deletesRemoveKeysUnderAValidLease() {
        store.save(USER, THREAD, KEY, state("a"));
        store.save(USER, THREAD, "other", state("b"));

        store.delete(USER, THREAD, "other");
        assertThat(store.get(USER, THREAD, "other", AgentState.class)).isEmpty();
        assertThat(store.exists(USER, THREAD)).isTrue();

        store.delete(USER, THREAD);
        assertThat(store.exists(USER, THREAD)).isFalse();
    }

    private void assertAllWritesRejected() {
        List<Consumer<FencedAgentStateStore>> writes = List.of(
                s -> s.save(USER, THREAD, KEY, state("x")),
                s -> s.saveIfVersion(USER, THREAD, KEY, state("x"), 1),
                s -> s.saveIfVersion(USER, THREAD, KEY, state("x"), 0),
                s -> s.saveIfVersion(USER, THREAD, KEY, state("x"), AgentStateStore.UNVERSIONED),
                s -> s.save(USER, THREAD, "memory_messages", List.of(new UserMessage("x"))),
                s -> s.delete(USER, THREAD, KEY),
                s -> s.delete(USER, THREAD));
        for (Consumer<FencedAgentStateStore> write : writes) {
            assertThatThrownBy(() -> write.accept(store)).isInstanceOf(LeaseLostException.class);
        }
    }

    private static AgentState state(String text) {
        AgentState state = AgentState.builder().sessionId(THREAD).userId(USER).build();
        state.contextMutable().add(new UserMessage(text));
        return state;
    }
}
