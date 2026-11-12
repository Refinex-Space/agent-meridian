package cn.refinex.meridian.runtime.file.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.crypto.Sha256;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.file.domain.FileErrorCode;
import cn.refinex.meridian.runtime.file.domain.FileNames;
import cn.refinex.meridian.runtime.file.domain.StoredFile;
import cn.refinex.meridian.runtime.file.infra.FileRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件的上传、查询与读取（{@code /v1/files}，ADR-0050）。在当前项目内执行：仓储从 {@code PlatformContext} 取租户与项目。先写对象
 * 存储再写记录，记录写入失败时对象成为孤儿但不可见（键里有文件 ID，不会被复用）。
 *
 * <p>方法会阻塞；线程安全：无状态。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileRepository files;
    private final ObjectStore objects;
    private final MediaTypes mediaTypes;
    private final FileProperties properties;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param files 文件仓储
     * @param objects 对象存储
     * @param mediaTypes 媒体类型检测
     * @param properties 文件配置
     * @param clock 时钟
     */
    public FileService(
            FileRepository files, ObjectStore objects, MediaTypes mediaTypes, FileProperties properties, Clock clock) {
        this.files = files;
        this.objects = objects;
        this.mediaTypes = mediaTypes;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 保存一个已落到本机临时文件的上传：检查大小、计算摘要、检测媒体类型、写对象存储与记录。
     *
     * @param content 上传内容所在的本机文件，调用方负责删除
     * @param clientFilename 表单中的文件名；可为空
     * @return 文件
     * @throws ValidationException 文件为空时
     * @throws MeridianException 超过大小上限时（{@code file.too_large}）
     */
    public StoredFile upload(Path content, String clientFilename) {
        long size = size(content);
        if (size == 0) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED, "文件为空", List.of(new FieldViolation("file", "不能为空文件")));
        }
        requireWithinLimit(size);
        PlatformContext context = PlatformContexts.current();
        String id = Ids.newId(IdPrefix.FILE);
        String filename = FileNames.normalize(clientFilename);
        String mediaType = mediaTypes.detect(content, filename);
        String key = StoredFile.objectKey(context.tenantId(), context.projectId(), id);
        String sha256 = Sha256.hex(content);
        try (InputStream in = Files.newInputStream(content)) {
            objects.put(key, in, size, mediaType);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传内容失败", e);
        }
        StoredFile file = new StoredFile(
                id,
                context.tenantId(),
                context.projectId(),
                filename,
                mediaType,
                size,
                sha256,
                key,
                context.actor(),
                clock.instant());
        files.insert(file);
        log.info("文件已上传 fileId={} size={} mediaType={}", id, size, mediaType);
        return file;
    }

    /**
     * 读取文件信息。
     *
     * @param id 文件 ID
     * @return 文件
     * @throws NotFoundException 不存在或不属于当前项目时（{@code file.not_found}）
     */
    public StoredFile get(String id) {
        return files.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * 打开文件内容。
     *
     * @param file 文件
     * @return 内容流，调用方关闭
     * @throws NotFoundException 对象已不存在时
     */
    public InputStream open(StoredFile file) {
        return objects.open(file.objectKey()).orElseThrow(() -> notFound(file.id()));
    }

    /**
     * 读取文件的全部内容（放进工作区时用，大小不超过上传上限）。
     *
     * @param file 文件
     * @return 内容
     * @throws NotFoundException 对象已不存在时
     */
    public byte[] read(StoredFile file) {
        return objects.get(file.objectKey()).orElseThrow(() -> notFound(file.id()));
    }

    /**
     * 内容的大小上限。
     *
     * @return 字节数
     */
    public long maxSize() {
        return properties.maxSize().toBytes();
    }

    /**
     * 检查内容是否在大小上限之内。
     *
     * @param size 字节数
     * @throws MeridianException 超过上限时（{@code file.too_large}）
     */
    public void requireWithinLimit(long size) {
        if (size > maxSize()) {
            throw tooLarge(maxSize());
        }
    }

    /**
     * 超过大小上限的错误。
     *
     * @param maxSize 上限，字节数
     * @return 异常
     */
    public static MeridianException tooLarge(long maxSize) {
        return new MeridianException(FileErrorCode.TOO_LARGE, "内容超过大小上限 " + maxSize + " 字节");
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException(FileErrorCode.NOT_FOUND, "文件不存在：" + id);
    }

    private static long size(Path content) {
        try {
            return Files.size(content);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传内容失败", e);
        }
    }
}
