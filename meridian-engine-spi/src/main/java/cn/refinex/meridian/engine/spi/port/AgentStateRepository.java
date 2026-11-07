package cn.refinex.meridian.engine.spi.port;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 智能体状态的带租约读写，{@code FencedAgentStateStore}（实现 AgentScope {@code AgentStateStore}）的存储后端，
 * 由 runtime 的 {@code session} 实现，表为 {@code rt.agent_states}，见架构设计「端口」「租约与 fencing」「崩溃恢复」。
 *
 * <p>每次写入（包括无条件写）都在同一事务里附加租约条件：{@code rt.turns} 中 {@link StateFence#leaseThreadId()}
 * 的活动轮次满足 {@code status='running' AND lease_owner = 本节点 AND lease_epoch = epoch}。不满足时抛
 * {@code LeaseLostException}，什么都不写。内容写入同时把 {@code content_epoch} 更新为当前 epoch。
 *
 * <p>状态以 JSON 文本存取，序列化由调用方（使用 AgentScope 的 Jackson 2）完成。方法是同步的：AgentScope
 * 的状态存储接口是同步接口。
 */
public interface AgentStateRepository {

    /**
     * 读取一个状态键。
     *
     * @param key 状态键
     * @return 状态 JSON 与版本；不存在时为空
     */
    Optional<StoredState> find(StateKey key);

    /**
     * 无条件写入（仍受租约约束）。
     *
     * @param key 状态键
     * @param json 状态 JSON
     * @param fence 租约条件
     * @return 写入后的版本号，从 1 开始递增
     * @throws cn.refinex.meridian.common.error.LeaseLostException 租约条件不满足时
     */
    long save(StateKey key, String json, StateFence fence);

    /**
     * 版本号匹配时写入（乐观并发），同时受租约约束。
     *
     * @param key 状态键
     * @param json 状态 JSON
     * @param expectedVersion 期望的当前版本；0 表示期望不存在
     * @param fence 租约条件
     * @return 写入后的版本号；版本不匹配时为空
     * @throws cn.refinex.meridian.common.error.LeaseLostException 租约条件不满足时
     */
    OptionalLong saveIfVersion(StateKey key, String json, long expectedVersion, StateFence fence);

    /**
     * 线程是否有任何状态。
     *
     * @param userId AgentScope 的用户 ID（{@code {project_id}:{end_user_id}}）
     * @param threadId 线程 ID
     * @return 存在任意状态键时为 true
     */
    boolean exists(String userId, String threadId);

    /**
     * 删除一个状态键，受租约约束。
     *
     * @param key 状态键
     * @param fence 租约条件
     * @throws cn.refinex.meridian.common.error.LeaseLostException 租约条件不满足时
     */
    void delete(StateKey key, StateFence fence);

    /**
     * 删除线程的全部状态，受租约约束。
     *
     * @param userId AgentScope 的用户 ID
     * @param threadId 线程 ID
     * @param fence 租约条件
     * @throws cn.refinex.meridian.common.error.LeaseLostException 租约条件不满足时
     */
    void deleteAll(String userId, String threadId, StateFence fence);

    /**
     * 列出用户名下有状态的线程。
     *
     * @param userId AgentScope 的用户 ID
     * @return 线程 ID 集合；没有时为空集合
     */
    Set<String> listThreadIds(String userId);

    /**
     * 读取线程最近一次内容写入的 epoch，崩溃恢复据此判断恢复方式（架构设计「崩溃恢复」）。
     *
     * @param userId AgentScope 的用户 ID
     * @param threadId 线程 ID
     * @return 各状态键中最大的 {@code content_epoch}；线程没有内容写入时为空
     */
    OptionalLong contentEpoch(String userId, String threadId);

    /**
     * 状态键，与 AgentScope 的 {@code (userId, sessionId, key)} 对应，其中 {@code sessionId} 是线程 ID。
     *
     * @param userId AgentScope 的用户 ID（{@code {project_id}:{end_user_id}}）；必填
     * @param threadId 线程 ID（{@code thr_…}）；必填
     * @param key AgentScope 的状态键名，例如 {@code agent_state}；必填
     */
    record StateKey(String userId, String threadId, String key) {

        /**
         * 创建状态键。
         *
         * @throws NullPointerException 缺少任一字段时
         */
        public StateKey {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * 写入时附加的租约条件，取自 {@link LeaseRegistry}。
     *
     * @param leaseThreadId 持有租约的线程（子线程写入时为父线程）；必填
     * @param epoch 期望的租约 epoch，大于 0
     * @param contentWrite 是否为内容写入；权限上下文的安装写入为 false，不更新 {@code content_epoch}
     */
    record StateFence(String leaseThreadId, long epoch, boolean contentWrite) {

        /**
         * 创建租约条件。
         *
         * @throws NullPointerException 缺少线程 ID 时
         * @throws IllegalArgumentException epoch 不大于 0 时
         */
        public StateFence {
            Objects.requireNonNull(leaseThreadId, "leaseThreadId");
            if (epoch < 1) {
                throw new IllegalArgumentException("租约 epoch 必须大于 0：" + epoch);
            }
        }
    }

    /**
     * 读出的状态。
     *
     * @param json 状态 JSON；必填
     * @param version 当前版本号，从 1 开始
     */
    record StoredState(String json, long version) {

        /**
         * 创建读出的状态。
         *
         * @throws NullPointerException 缺少 JSON 时
         */
        public StoredState {
            Objects.requireNonNull(json, "json");
        }
    }
}
