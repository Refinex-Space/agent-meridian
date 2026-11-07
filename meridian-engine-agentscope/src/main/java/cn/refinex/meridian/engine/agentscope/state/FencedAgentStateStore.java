package cn.refinex.meridian.engine.agentscope.state;

import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StateFence;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StateKey;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StoredState;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 带租约校验的智能体状态存储，实现 AgentScope 2.0.4 的 {@link AgentStateStore} 全部方法并支持版本号。
 *
 * <p>AgentScope 写状态的时机不止"调用结束"：用户中断、非中断错误、优雅停机、权限上下文替换都会写，而
 * {@code HarnessAgent} 不暴露冲突策略，所以 fencing 只能在存储层实现（架构设计「租约与 fencing」）。每次写入
 * （含 {@code saveIfVersion} 的无条件写与删除）都先按线程 ID 从 {@link LeaseRegistry} 取租约，再交给
 * {@link AgentStateRepository} 在同一事务里校验 {@code rt.turns} 中该线程活动轮次的状态、节点与 epoch；
 * 本节点没有该线程的租约，或数据库条件不满足，都抛 {@link LeaseLostException}，什么都不写。
 *
 * <ul>
 *   <li>{@code content_epoch}：除权限上下文的安装写入（{@code LeaseRegistry} 条目上标记）外，每次写入都是内容写入，
 *       由端口把 {@code content_epoch} 更新为当前 epoch，供崩溃恢复判断（架构设计「崩溃恢复」）。
 *   <li>子线程：{@code LeaseRegistry} 对登记过的子线程返回父线程的租约，所以子线程的写入以父轮次的租约为条件。
 *   <li>版本号：{@code saveIfVersion} 的语义与 2.0.4 一致，期望版本 0 表示"不存在才写"，
 *       {@link AgentStateStore#UNVERSIONED} 表示无条件写，冲突时返回 {@code UNVERSIONED} 且不写。
 * </ul>
 *
 * <p>状态以 AgentScope 自己的 {@link JsonCodec}（Jackson 2）序列化为 JSON 文本，列表状态存为 JSON 数组。
 *
 * <p>线程安全：无状态，可被所有智能体实例共享。方法是同步的、会阻塞（runtime 的端口实现访问数据库）：
 * AgentScope 在 {@code boundedElastic} 或停机监控线程上调用它，平台自己的调用（权限安装）经 {@code Blocking}。
 */
public final class FencedAgentStateStore implements AgentStateStore {

    private final AgentStateRepository repository;
    private final LeaseRegistry leases;

    /**
     * 创建状态存储。
     *
     * @param repository 带租约条件的状态读写端口
     * @param leases 本节点持有的租约
     */
    public FencedAgentStateStore(AgentStateRepository repository, LeaseRegistry leases) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        repository.save(stateKey(userId, sessionId, key), codec().toJson(value), fence(sessionId));
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        Optional<StoredState> stored = repository.find(stateKey(userId, sessionId, key));
        // 2.0.4 的约定：支持版本号的后端对不存在的键报告版本 0
        return stored.map(s -> new VersionedState<>(codec().fromJson(s.json(), type), s.version()))
                .orElseGet(() -> new VersionedState<>(null, 0L));
    }

    @Override
    public long saveIfVersion(String userId, String sessionId, String key, State value, long expectedVersion) {
        StateKey stateKey = stateKey(userId, sessionId, key);
        String json = codec().toJson(value);
        StateFence fence = fence(sessionId);
        if (expectedVersion == UNVERSIONED) {
            return repository.save(stateKey, json, fence);
        }
        OptionalLong written = repository.saveIfVersion(stateKey, json, expectedVersion, fence);
        return written.isPresent() ? written.getAsLong() : UNVERSIONED;
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        repository.save(stateKey(userId, sessionId, key), codec().toJson(values), fence(sessionId));
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return repository.find(stateKey(userId, sessionId, key)).map(s -> codec().fromJson(s.json(), type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        Optional<StoredState> stored = repository.find(stateKey(userId, sessionId, key));
        if (stored.isEmpty()) {
            return List.of();
        }
        JsonCodec codec = codec();
        List<?> raw = codec.fromJson(stored.get().json(), List.class);
        List<T> items = new ArrayList<>(raw.size());
        for (Object item : raw) {
            items.add(codec.convertValue(item, itemType));
        }
        return items;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return repository.exists(requireUserId(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        repository.deleteAll(requireUserId(userId), sessionId, fence(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        repository.delete(stateKey(userId, sessionId, key), fence(sessionId));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return repository.listThreadIds(requireUserId(userId));
    }

    /**
     * 取本次写入的租约条件。线程 ID 即 AgentScope 的 {@code sessionId}（架构设计「身份映射」）。
     *
     * <p>同一节点上一个线程的上一次执行在 {@code doFinally} 完成之前不会被再次认领，条目也在执行结束时才移除，
     * 所以这里按线程 ID 查到的 epoch 就是正在执行的那一次（架构设计「一轮执行的生命周期」中"同节点重新认领"）。
     */
    private StateFence fence(String threadId) {
        LeaseRegistry.Lease lease = leases.find(threadId)
                .orElseThrow(() -> new LeaseLostException("本节点没有线程 " + threadId + " 的租约，拒绝写入智能体状态"));
        return new StateFence(lease.leaseThreadId(), lease.epoch(), !lease.permissionInstall());
    }

    private static StateKey stateKey(String userId, String threadId, String key) {
        return new StateKey(requireUserId(userId), threadId, key);
    }

    /** 平台总是设置 {@code userId}（{@code {project_id}:{end_user_id}}）；AgentScope 的匿名用户在这里是编程错误。 */
    private static String requireUserId(String userId) {
        return Objects.requireNonNull(userId, "AgentScope userId 必须由平台按「身份映射」设置");
    }

    private static JsonCodec codec() {
        return JsonUtils.getJsonCodec();
    }
}
