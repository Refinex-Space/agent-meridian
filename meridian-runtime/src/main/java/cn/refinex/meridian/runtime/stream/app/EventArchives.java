package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 归档事件文件的读写（架构设计「数据保留」，ADR-0049）：gzip 压缩的 JSON Lines，每行一个事件信封（与 {@code GET .../events} 的一项
 * 相同），按 seq 升序；键 {@code rt/{tenant}/{session}/events/archive-{最大 seq}-{ULID}.jsonl.gz}，在会话的大对象前缀之下，清理会话时随前缀
 * 删除。文件写入后不再修改：重新归档或保留清理写新键，旧键在提交后删除，所以读到的内容可以按键缓存。
 *
 * <p>线程安全：缓存可并发访问。方法会阻塞（对象存储）。
 */
@Service
public class EventArchives {

    /** 文件的内容类型。 */
    static final String CONTENT_TYPE = "application/gzip";

    /** 缓存中至多保留的事件条数（按事件计重）。 */
    static final long CACHE_MAX_EVENTS = 200_000;

    private final ObjectStore objects;
    private final Cache<String, List<StoredEvent>> cache = Caffeine.newBuilder()
            .maximumWeight(CACHE_MAX_EVENTS)
            .weigher((String key, List<StoredEvent> events) -> Math.max(1, events.size()))
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    /**
     * 创建读写入口。
     *
     * @param objects 对象存储
     */
    public EventArchives(ObjectStore objects) {
        this.objects = objects;
    }

    /**
     * 写一个归档文件。
     *
     * @param tenantId 租户 ID
     * @param sessionId 会话 ID
     * @param coveredSeq 文件覆盖到的最大 seq（写进键名，便于排查）
     * @param events 事件，按 seq 升序
     * @return 对象键
     */
    public String write(String tenantId, String sessionId, long coveredSeq, List<StoredEvent> events) {
        String key = prefix(tenantId, sessionId) + "archive-" + coveredSeq + "-" + Ids.newUlid() + ".jsonl.gz";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
            for (StoredEvent event : events) {
                out.write(EventEnvelopes.json(event));
                out.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写归档文件失败", e);
        }
        objects.put(key, bytes.toByteArray(), CONTENT_TYPE);
        return key;
    }

    /**
     * 读一个归档文件。
     *
     * @param key 对象键
     * @param tenantId 会话的租户（信封中没有，写回事件行）
     * @param projectId 会话的项目
     * @return 事件，按 seq 升序；文件不存在（已被新的归档取代并删除）时为空
     */
    public Optional<List<StoredEvent>> read(String key, String tenantId, String projectId) {
        List<StoredEvent> cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<InputStream> stream = objects.open(key);
        if (stream.isEmpty()) {
            return Optional.empty();
        }
        List<StoredEvent> events = new ArrayList<>();
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(new GZIPInputStream(stream.get()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(parse(line, tenantId, projectId));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读归档文件失败：" + key, e);
        }
        List<StoredEvent> loaded = List.copyOf(events);
        cache.put(key, loaded);
        return Optional.of(loaded);
    }

    /**
     * 删除一个归档文件（被取代或过了保留期）。
     *
     * @param key 对象键；为空时什么都不做
     */
    public void delete(String key) {
        if (key != null) {
            cache.invalidate(key);
            objects.delete(key);
        }
    }

    /**
     * 会话的归档文件前缀。
     *
     * @param tenantId 租户 ID
     * @param sessionId 会话 ID
     * @return {@code rt/{tenant}/{session}/events/}
     */
    public static String prefix(String tenantId, String sessionId) {
        return ObjectStoreBlobStore.sessionPrefix(tenantId, sessionId) + "events/";
    }

    private static StoredEvent parse(String line, String tenantId, String projectId) {
        JsonNode node = JsonMappers.lenient().readTree(line);
        return new StoredEvent(
                node.get("session_id").asString(),
                node.get("seq").asLong(),
                node.get("id").asString(),
                tenantId,
                projectId,
                text(node, "thread_id"),
                text(node, "turn_id"),
                EventType.fromValue(node.get("type").asString()),
                node.get("data").toString(),
                Instant.parse(node.get("created_at").asString()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
