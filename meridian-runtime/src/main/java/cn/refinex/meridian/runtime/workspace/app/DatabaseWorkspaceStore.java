package cn.refinex.meridian.runtime.workspace.app;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository.Content;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository.Row;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository.Scope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/**
 * {@link WorkspaceStorePort} 的实现（架构设计「文件系统装配」「存储分工」）：{@code MeridianBaseStore} 的存储后端。
 *
 * <ul>
 *   <li>值（JSON 对象）序列化后不超过 {@value #INLINE_LIMIT_BYTES} 字节时内联在 {@code rt.workspace_entries}，否则写进对象存储
 *       （键 {@code rt/{tenant}/{session}/workspace/{ulid}}，与会话的其他大对象同一前缀，删除会话时一并清除），表中只存键。
 *   <li>版本号从 1 开始，每次写入加一；{@link #putIfVersion} 以版本号做乐观并发，0 表示"不存在才创建"，{@code RemoteFilesystem}
 *       的写入与编辑都依赖它。
 *   <li>对象先写、行后更新：行更新失败（版本不匹配）时删除新写的对象；成功后删除被替换的旧对象。删除失败只记日志，留下的孤儿
 *       对象随会话前缀清除。
 * </ul>
 *
 * <p>命名空间以租户与项目开头，由 {@code WorkspaceNamespaces} 从 {@code PlatformContext} 生成；本类据此确定行的租户与项目，
 * 不读取调用线程上的上下文。方法同步、会阻塞：AgentScope 的 {@code BaseStore} 是同步接口，工具在 {@code boundedElastic}
 * 线程上调用它。线程安全：无状态。
 */
@Service
public class DatabaseWorkspaceStore implements WorkspaceStorePort {

    /** 内联存储的上限，单位字节。架构设计「存储分工」规定 64 KB 以内的内容存数据库。 */
    public static final int INLINE_LIMIT_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(DatabaseWorkspaceStore.class);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final WorkspaceEntryRepository repository;
    private final ObjectStore objects;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建工作区存储。
     *
     * @param repository 条目仓储
     * @param objects 对象存储
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param clock 时钟
     */
    public DatabaseWorkspaceStore(
            WorkspaceEntryRepository repository, ObjectStore objects, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.objects = objects;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Optional<Item> get(List<String> namespace, String key) {
        return repository.find(scope(namespace), namespace, key, false).map(this::toItem);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        Scope scope = scope(namespace);
        Content content = store(namespace, value);
        String replaced = transactions.execute(status -> {
            Optional<Row> current = repository.find(scope, namespace, key, true);
            if (current.isEmpty()) {
                repository.insert(scope, namespace, key, content, clock.instant());
                return null;
            }
            repository.update(scope, namespace, key, content, current.get().version(), clock.instant());
            return current.get().objectKey();
        });
        deleteObject(replaced);
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        Scope scope = scope(namespace);
        Content content = store(namespace, value);
        WriteOutcome outcome = Objects.requireNonNull(transactions.execute(status -> {
            if (expectedVersion == 0) {
                return new WriteOutcome(repository.insert(scope, namespace, key, content, clock.instant()), null);
            }
            Optional<Row> current = repository.find(scope, namespace, key, true);
            if (current.isEmpty() || current.get().version() != expectedVersion) {
                return new WriteOutcome(false, null);
            }
            repository.update(scope, namespace, key, content, expectedVersion, clock.instant());
            return new WriteOutcome(true, current.get().objectKey());
        }));
        if (!outcome.written()) {
            deleteObject(content.objectKey());
            return false;
        }
        deleteObject(outcome.replacedObjectKey());
        return true;
    }

    @Override
    public List<Item> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0 || offset < 0) {
            throw new IllegalArgumentException("分页参数不合法：limit=" + limit + " offset=" + offset);
        }
        return repository.search(scope(namespace), namespace, limit, offset).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public void delete(List<String> namespace, String key) {
        Optional<String> objectKey =
                transactions.execute(status -> repository.delete(scope(namespace), namespace, key));
        deleteObject(objectKey == null ? null : objectKey.orElse(null));
    }

    /** 序列化值：不超过上限时内联，否则写进对象存储。 */
    private Content store(List<String> namespace, Map<String, Object> value) {
        String json = JsonMappers.strict().writeValueAsString(value);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= INLINE_LIMIT_BYTES) {
            return new Content(bytes.length, json, null);
        }
        String objectKey = "rt/" + namespace.getFirst() + "/" + namespace.getLast() + "/workspace/" + Ids.newUlid();
        objects.put(objectKey, bytes, "application/json");
        return new Content(bytes.length, null, objectKey);
    }

    private Item toItem(Row row) {
        String json = row.inlineJson() != null
                ? row.inlineJson()
                : objects.get(row.objectKey())
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .orElseThrow(() -> new IllegalStateException("工作区条目的对象不存在：" + row.objectKey()));
        return new Item(row.namespace(), row.key(), JsonMappers.lenient().readValue(json, MAP), row.version());
    }

    private void deleteObject(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            objects.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("删除被替换的工作区对象失败，随会话前缀清除 key={}", objectKey, e);
        }
    }

    /** 命名空间的前两段是租户与项目（{@code WorkspaceNamespaces}）。 */
    private static Scope scope(List<String> namespace) {
        if (namespace == null || namespace.size() < 2 || namespace.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("工作区命名空间必须以租户与项目开头：" + namespace);
        }
        return new Scope(namespace.get(0), namespace.get(1));
    }

    /** 条件写入的结果。 */
    private record WriteOutcome(boolean written, String replacedObjectKey) {}
}
