package cn.refinex.meridian.engine.spi.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工作区 KV 存储，{@code MeridianBaseStore}（实现 AgentScope {@code BaseStore}）的存储后端，由 runtime 的
 * {@code workspace} 实现，表为 {@code rt.workspace_entries}，见架构设计「端口」「文件系统装配」。
 *
 * <p>命名空间由 {@code NamespaceFactory} 按 {@code [tenant_id, project_id, end_user_id, session_id]} 生成，
 * 四个值都取自 {@code PlatformContext}。64 KB 以内的值内联在表中，更大的进对象存储，由实现处理。方法是同步的：
 * AgentScope 的 {@code BaseStore} 是同步接口。
 */
public interface WorkspaceStorePort {

    /**
     * 读取一个条目。
     *
     * @param namespace 命名空间
     * @param key 键
     * @return 条目；不存在时为空
     */
    Optional<Item> get(List<String> namespace, String key);

    /**
     * 无条件写入，版本号加一。
     *
     * @param namespace 命名空间
     * @param key 键
     * @param value 值（JSON 对象）
     */
    void put(List<String> namespace, String key, Map<String, Object> value);

    /**
     * 版本号匹配时写入。{@code MeridianBaseStore} 必须覆盖 {@code putIfVersion}：AgentScope 的默认实现返回 false，
     * 会导致所有写入失败（架构设计「文件系统装配」）。
     *
     * @param namespace 命名空间
     * @param key 键
     * @param value 值
     * @param expectedVersion 期望的当前版本；0 表示期望不存在
     * @return 写入成功时为 true，版本不匹配时为 false
     */
    boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion);

    /**
     * 列出命名空间（含子命名空间）下的条目，按键排序。
     *
     * @param namespace 命名空间前缀
     * @param limit 最多返回的条数，大于 0
     * @param offset 跳过的条数，不小于 0
     * @return 条目；没有时为空列表
     */
    List<Item> search(List<String> namespace, int limit, int offset);

    /**
     * 删除一个条目。不存在时无副作用。
     *
     * @param namespace 命名空间
     * @param key 键
     */
    void delete(List<String> namespace, String key);

    /**
     * 工作区的一个条目。
     *
     * @param namespace 命名空间；必填
     * @param key 键；必填
     * @param value 值（JSON 对象）；必填
     * @param version 版本号，从 1 开始
     */
    record Item(List<String> namespace, String key, Map<String, Object> value, long version) {

        /**
         * 创建条目。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Item {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            namespace = List.copyOf(namespace);
            value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }
}
