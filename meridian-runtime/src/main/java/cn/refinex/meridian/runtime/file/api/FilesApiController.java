package cn.refinex.meridian.runtime.file.api;

import cn.refinex.meridian.api.model.UploadedFile;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.runtime.file.app.FileService;
import cn.refinex.meridian.runtime.file.domain.StoredFile;
import cn.refinex.meridian.runtime.openapi.FilesApi;
import cn.refinex.meridian.runtime.web.ApiTimes;
import cn.refinex.meridian.runtime.web.IdempotentRequests;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 文件的公开 API（{@code /v1/files}，ADR-0050）：上传要 {@code files:write}，查询与下载要 {@code sessions:read}。
 *
 * <p>上传的表单由框架解析（超过 {@code spring.webflux.multipart.max-disk-usage-per-part} 的部分在解析时失败），文件部分先落到本机
 * 临时文件，再在虚拟线程上检测、计算摘要并写入对象存储，临时文件随后删除。幂等键的指纹是文件名与内容摘要。
 */
@RestController
public class FilesApiController implements FilesApi {

    private final FileService files;
    private final IdempotentRequests idempotent;

    /**
     * 创建处理器。
     *
     * @param files 文件用例
     * @param idempotent POST 的幂等执行
     */
    public FilesApiController(FileService files, IdempotentRequests idempotent) {
        this.files = files;
        this.idempotent = idempotent;
    }

    @Override
    public Mono<ResponseEntity<UploadedFile>> uploadFile(Part file, String idempotencyKey, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.FILES_WRITE);
        if (!(file instanceof FilePart filePart)) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED, "缺少文件", List.of(new FieldViolation("file", "必须是文件部分")));
        }
        return Mono.usingWhen(
                Blocking.call(() -> Files.createTempFile("meridian-upload-", ".part")),
                temp -> filePart.transferTo(temp)
                        .then(Mono.defer(() -> upload(exchange, idempotencyKey, filePart.filename(), temp))),
                temp -> Blocking.run(() -> Files.deleteIfExists(temp)));
    }

    @Override
    public Mono<ResponseEntity<UploadedFile>> getFile(String fileId, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        return Blocking.call(() -> toDto(files.get(fileId))).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Resource>> getFileContent(String fileId, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        return Blocking.call(() -> {
            StoredFile file = files.get(fileId);
            byte[] content;
            try (InputStream in = files.open(file)) {
                content = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("读取文件内容失败", e);
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.mediaType()))
                    .contentLength(content.length)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename(file.filename(), StandardCharsets.UTF_8)
                                    .build()
                                    .toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body((Resource) new ByteArrayResource(content));
        });
    }

    private Mono<ResponseEntity<UploadedFile>> upload(
            ServerWebExchange exchange, String idempotencyKey, String filename, Path temp) {
        return Blocking.call(() -> idempotencyKey == null ? null : FileService.sha256(temp))
                .defaultIfEmpty("")
                .flatMap(sha256 -> idempotent.execute(
                        exchange,
                        idempotencyKey,
                        Map.of("filename", String.valueOf(filename), "sha256", sha256),
                        HttpStatus.CREATED,
                        UploadedFile.class,
                        () -> toDto(files.upload(temp, filename))));
    }

    private static UploadedFile toDto(StoredFile file) {
        return new UploadedFile()
                .id(file.id())
                .filename(file.filename())
                .mediaType(file.mediaType())
                .sizeBytes(file.sizeBytes())
                .sha256(file.sha256())
                .createdAt(ApiTimes.utc(file.createdAt()));
    }
}
