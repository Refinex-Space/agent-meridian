package cn.refinex.meridian.runtime.workspace.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.WorkspaceFiles;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.file.app.FileService;
import cn.refinex.meridian.runtime.file.app.MediaTypes;
import cn.refinex.meridian.runtime.sandbox.infra.SandboxOutputRepository;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.workspace.domain.WorkspaceApiPaths;
import cn.refinex.meridian.runtime.workspace.domain.WorkspaceErrorCode;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 工作区 API 的用例（{@code /v1/sessions/{id}/workspace/…}，ADR-0050）。档位取会话的环境修订：
 *
 * <ul>
 *   <li>{@code none} 档读写智能体使用的同一份平台存储（命名空间与值的格式见 {@link WorkspaceFiles}），写入覆盖，受保护路径
 *       只读，会话已终止时不能写。
 *   <li>{@code sandbox} 档只读 {@code outputs/} 的副本（每轮结束时同步，ADR-0037），不能写。
 * </ul>
 *
 * <p>会话不属于当前项目与不存在相同。方法会阻塞，在 {@code PlatformContext} 中执行；线程安全：无状态。
 */
@Service
public class WorkspaceFileService {

    /** 读取环境配置的等待上限。 */
    static final Duration CONFIG_TIMEOUT = Duration.ofSeconds(30);

    private final SessionService sessions;
    private final AgentConfigPort configs;
    private final WorkspaceStorePort store;
    private final WorkspaceEntryRepository entries;
    private final SandboxOutputRepository outputs;
    private final ObjectStore objects;
    private final MediaTypes mediaTypes;
    private final FileService files;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param sessions 会话用例，确认会话属于当前项目
     * @param configs 配置端口，读取环境的档位
     * @param store {@code none} 档的工作区存储
     * @param entries 工作区条目的列表查询
     * @param outputs 产物副本的清单
     * @param objects 对象存储，读取产物副本
     * @param mediaTypes 媒体类型检测
     * @param files 文件用例，取大小上限
     * @param clock 时钟
     */
    public WorkspaceFileService(
            SessionService sessions,
            AgentConfigPort configs,
            WorkspaceStorePort store,
            WorkspaceEntryRepository entries,
            SandboxOutputRepository outputs,
            ObjectStore objects,
            MediaTypes mediaTypes,
            FileService files,
            Clock clock) {
        this.sessions = sessions;
        this.configs = configs;
        this.store = store;
        this.entries = entries;
        this.outputs = outputs;
        this.objects = objects;
        this.mediaTypes = mediaTypes;
        this.files = files;
        this.clock = clock;
    }

    /**
     * 读取一个文件。
     *
     * @param sessionId 会话 ID
     * @param path 相对工作区根的路径
     * @return 内容与检测的媒体类型
     * @throws MeridianException 路径不合法（{@code workspace.invalid_path}）、会话不存在（{@code session.not_found}）、文件不存在
     *     （{@code workspace.file_not_found}）时
     */
    public Content read(String sessionId, String path) {
        WorkspaceApiPaths.requireValid(path);
        Session session = sessions.get(sessionId);
        byte[] content = sandboxTier(session) ? readOutput(session, path) : readStored(session, path);
        return new Content(content, mediaTypes.detect(content, path.substring(path.lastIndexOf('/') + 1)));
    }

    /**
     * 写入一个文件，已存在时覆盖（只支持 {@code none} 档）。
     *
     * @param sessionId 会话 ID
     * @param path 相对工作区根的路径
     * @param content 内容
     * @return 写入后的文件信息
     * @throws MeridianException 路径不合法、受保护路径（{@code workspace.protected_path}）、{@code sandbox} 档
     *     （{@code workspace.read_only}）、会话已终止（{@code session.terminated}）、超过大小上限（{@code file.too_large}）时
     */
    public Entry write(String sessionId, String path, byte[] content) {
        WorkspaceApiPaths.requireValid(path);
        if (WorkspaceApiPaths.isProtected(path)) {
            throw new MeridianException(WorkspaceErrorCode.PROTECTED_PATH, "受保护路径只读：" + path);
        }
        files.requireWithinLimit(content.length);
        Session session = sessions.get(sessionId);
        SessionService.requireWritable(session);
        if (sandboxTier(session)) {
            throw new ConflictException(WorkspaceErrorCode.READ_ONLY, "沙箱档的工作区经 API 只读 outputs/ 的副本");
        }
        Instant now = clock.instant();
        store.put(namespace(session), WorkspaceFiles.key(path), WorkspaceFiles.encode(content, now));
        return new Entry(path, (long) content.length, now);
    }

    /**
     * 列出文件，按路径的字节序。
     *
     * @param sessionId 会话 ID
     * @param prefix 路径前缀；可为空
     * @param page 分页参数，游标为上一页最后的路径
     * @return 一页文件
     * @throws MeridianException 前缀不合法、会话不存在时
     */
    public PageResult<Entry> list(String sessionId, String prefix, PageRequest page) {
        String validPrefix = WorkspaceApiPaths.requireValidPrefix(prefix);
        Session session = sessions.get(sessionId);
        String after = page.after() == null ? null : page.after().key(0);
        List<Entry> rows = sandboxTier(session)
                ? listOutputs(session, validPrefix, after, page)
                : listStored(session, validPrefix, after, page);
        return PageResult.of(rows, page, entry -> Cursor.of(entry.path()));
    }

    private byte[] readStored(Session session, String path) {
        return store.get(namespace(session), WorkspaceFiles.key(path))
                .flatMap(item -> WorkspaceFiles.decode(item.value()))
                .orElseThrow(() -> fileNotFound(path));
    }

    private byte[] readOutput(Session session, String path) {
        if (!path.startsWith(WorkspaceApiPaths.OUTPUTS_PREFIX)) {
            throw fileNotFound(path);
        }
        return outputs.find(session.id(), path.substring(WorkspaceApiPaths.OUTPUTS_PREFIX.length()))
                .flatMap(output -> objects.get(output.objectKey()))
                .orElseThrow(() -> fileNotFound(path));
    }

    private List<Entry> listStored(Session session, String prefix, String after, PageRequest page) {
        return entries
                .list(
                        new WorkspaceEntryRepository.Scope(session.tenantId(), session.projectId()),
                        namespace(session),
                        WorkspaceFiles.key(prefix),
                        after == null ? null : WorkspaceFiles.key(after),
                        page.fetchSize())
                .stream()
                .map(row -> new Entry(WorkspaceFiles.relativePath(row.key()), row.contentSize(), row.updatedAt()))
                .toList();
    }

    private List<Entry> listOutputs(Session session, String prefix, String after, PageRequest page) {
        String outputsPrefix = WorkspaceApiPaths.OUTPUTS_PREFIX;
        String relativePrefix;
        if (prefix.startsWith(outputsPrefix)) {
            relativePrefix = prefix.substring(outputsPrefix.length());
        } else if (outputsPrefix.startsWith(prefix)) {
            relativePrefix = "";
        } else {
            return List.of();
        }
        String relativeAfter =
                after == null || !after.startsWith(outputsPrefix) ? null : after.substring(outputsPrefix.length());
        return outputs.list(session.id(), relativePrefix, relativeAfter, page.fetchSize()).stream()
                .map(output -> new Entry(outputsPrefix + output.path(), output.size(), output.syncedAt()))
                .toList();
    }

    private boolean sandboxTier(Session session) {
        EnvironmentSpec environment = configs.environment(session.environmentId(), session.environmentRevision())
                .contextWrite(PlatformContexts.write(PlatformContexts.current()))
                .block(CONFIG_TIMEOUT);
        if (environment == null) {
            throw new IllegalStateException("会话的环境配置不存在：" + session.environmentId());
        }
        return environment.tier() == EnvironmentSpec.Tier.SANDBOX;
    }

    private static List<String> namespace(Session session) {
        return WorkspaceFiles.namespace(session.tenantId(), session.projectId(), session.endUserId(), session.id());
    }

    private static NotFoundException fileNotFound(String path) {
        return new NotFoundException(WorkspaceErrorCode.FILE_NOT_FOUND, "工作区文件不存在：" + path);
    }

    /**
     * 文件内容。
     *
     * @param bytes 内容
     * @param mediaType 服务端检测的媒体类型
     */
    public record Content(byte[] bytes, String mediaType) {}

    /**
     * 一个工作区文件的信息。
     *
     * @param path 相对工作区根的路径
     * @param sizeBytes 字节数；没有记录时为空
     * @param updatedAt 最近一次写入（或同步）的时间
     */
    public record Entry(String path, Long sizeBytes, Instant updatedAt) {}
}
