package cn.refinex.meridian.runtime.file.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.text.FileNames;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.error.RetryableTurnException;
import cn.refinex.meridian.engine.spi.port.WorkspaceFiles;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import cn.refinex.meridian.runtime.file.domain.FileReferences;
import cn.refinex.meridian.runtime.file.domain.FileStagingException;
import cn.refinex.meridian.runtime.file.domain.StoredFile;
import cn.refinex.meridian.runtime.file.infra.FileRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 轮次开始时把本轮输入引用的平台文件放进工作区（ADR-0050），在准备会话沙箱之后、调用内核之前执行：
 *
 * <ul>
 *   <li>路径 {@code uploads/{file_id}/{filename}}，文件名取块中给出的（发送事件时已规范化），没有时取文件的名字。
 *   <li>{@code none} 档（没有会话沙箱）经 {@link WorkspaceStorePort#putIfVersion} 以"不存在才创建"写入：同一轮被恢复重放时不覆盖智能体
 *       在上一次执行中的修改。
 *   <li>{@code sandbox} 档经 {@link SandboxDriver#writeFiles} 写进沙箱（覆盖）。
 * </ul>
 *
 * <p>引用的文件或其内容已不存在时跳过（翻译器告诉模型文件不可用）；对象存储读取失败抛可重试的 {@link FileStagingException}，沙箱写入失败的
 * {@code SandboxUnavailableException} 原样抛出。方法会阻塞，在 {@code PlatformContext} 中执行；线程安全：无状态。
 */
@Service
public class TurnFileStager {

    private static final Logger log = LoggerFactory.getLogger(TurnFileStager.class);

    /** 写入沙箱的等待上限：与驱动的单次请求超时同一量级，超过时本轮置回队列。 */
    static final Duration SANDBOX_WRITE_TIMEOUT = Duration.ofMinutes(2);

    private final FileRepository files;
    private final FileService contents;
    private final WorkspaceStorePort workspace;
    private final ObjectProvider<SandboxDriver> drivers;
    private final Clock clock;

    /**
     * 创建放置器。
     *
     * @param files 文件仓储
     * @param contents 文件内容的读取
     * @param workspace {@code none} 档的工作区存储
     * @param drivers 沙箱驱动；没有启用会话沙箱时不存在
     * @param clock 时钟
     */
    public TurnFileStager(
            FileRepository files,
            FileService contents,
            WorkspaceStorePort workspace,
            ObjectProvider<SandboxDriver> drivers,
            Clock clock) {
        this.files = files;
        this.contents = contents;
        this.workspace = workspace;
        this.drivers = drivers;
        this.clock = clock;
    }

    /**
     * 放置本轮输入引用的文件。
     *
     * @param turn 本轮的上下文
     * @param input 本轮的输入
     * @param sandbox 会话沙箱；{@code none} 档为 null
     * @return 文件 ID → 相对工作区根的路径；没有引用时为空映射
     * @throws RetryableTurnException 对象存储或沙箱暂时不可用时
     */
    public Map<String, String> stage(TurnContext turn, TurnInput input, SandboxHandle sandbox) {
        List<FileReferences.Reference> references = FileReferences.of(input);
        if (references.isEmpty()) {
            return Map.of();
        }
        Map<String, StoredFile> stored =
                files
                        .findAll(references.stream()
                                .map(FileReferences.Reference::fileId)
                                .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(StoredFile::id, Function.identity()));
        Map<String, String> paths = new LinkedHashMap<>();
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        for (FileReferences.Reference reference : references) {
            StoredFile file = stored.get(reference.fileId());
            if (file == null || paths.containsKey(file.id())) {
                continue;
            }
            byte[] content = read(file);
            if (content == null) {
                continue;
            }
            String name = reference.filename() == null ? file.filename() : FileNames.normalize(reference.filename());
            String path = WorkspaceFiles.uploadPath(file.id(), name);
            paths.put(file.id(), path);
            payloads.put(path, content);
        }
        if (sandbox == null) {
            List<String> namespace = WorkspaceFiles.namespace(
                    turn.platform().tenantId(),
                    turn.platform().projectId(),
                    turn.platform().endUserId(),
                    turn.platform().sessionId());
            payloads.forEach((path, content) -> workspace.putIfVersion(
                    namespace, WorkspaceFiles.key(path), WorkspaceFiles.encode(content, clock.instant()), 0));
        } else {
            drivers.getObject().writeFiles(sandbox, payloads).block(SANDBOX_WRITE_TIMEOUT);
        }
        log.info("本轮引用的文件已放进工作区 turnId={} files={} sandbox={}", turn.turnId(), paths.size(), sandbox != null);
        return paths;
    }

    /** 读取内容；对象已不存在时为 null（跳过）。 */
    private byte[] read(StoredFile file) {
        try {
            return contents.read(file);
        } catch (NotFoundException e) {
            log.warn("引用的文件内容已不存在，跳过 fileId={}", file.id());
            return null;
        } catch (RuntimeException e) {
            throw new FileStagingException("读取文件失败：" + file.id(), e);
        }
    }
}
