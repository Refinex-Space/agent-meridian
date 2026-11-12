package cn.refinex.meridian.runtime.workspace.api;

import cn.refinex.meridian.api.model.WorkspaceFile;
import cn.refinex.meridian.api.model.WorkspaceFilePage;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.file.app.FileService;
import cn.refinex.meridian.runtime.openapi.WorkspaceApi;
import cn.refinex.meridian.runtime.web.ApiTimes;
import cn.refinex.meridian.runtime.workspace.app.WorkspaceFileService;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 工作区的公开 API（ADR-0050）：读取与列表要 {@code sessions:read}，写入要 {@code sessions:write}。
 *
 * <p>写入的请求体不经生成接口的 {@code Mono<Resource>}（编解码器默认只聚合 256 KB），直接按文件的大小上限聚合原始请求体，超过时
 * 413 {@code file.too_large}。响应内容的 {@code Content-Type} 是服务端检测的类型，另带 {@code X-Content-Type-Options: nosniff}。
 */
@RestController
public class WorkspaceApiController implements WorkspaceApi {

    private final WorkspaceFileService workspace;
    private final FileService files;

    /**
     * 创建处理器。
     *
     * @param workspace 工作区用例
     * @param files 文件用例，取大小上限
     */
    public WorkspaceApiController(WorkspaceFileService workspace, FileService files) {
        this.workspace = workspace;
        this.files = files;
    }

    @Override
    public Mono<ResponseEntity<Resource>> getWorkspaceFile(String sessionId, String path, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        return Blocking.call(() -> workspace.read(sessionId, path))
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(content.mediaType()))
                        .contentLength(content.bytes().length)
                        .header("X-Content-Type-Options", "nosniff")
                        .body((Resource) new ByteArrayResource(content.bytes())));
    }

    @Override
    public Mono<ResponseEntity<WorkspaceFile>> putWorkspaceFile(
            String sessionId, String path, Mono<Resource> body, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        long maxSize = files.maxSize();
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > maxSize) {
            throw FileService.tooLarge(maxSize);
        }
        return DataBufferUtils.join(exchange.getRequest().getBody(), (int) Math.min(maxSize, Integer.MAX_VALUE - 8))
                .map(WorkspaceApiController::bytes)
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, error -> FileService.tooLarge(maxSize))
                .flatMap(content -> Blocking.call(() -> workspace.write(sessionId, path, content)))
                .map(entry -> ResponseEntity.ok(toDto(entry)));
    }

    @Override
    public Mono<ResponseEntity<WorkspaceFilePage>> listWorkspaceFiles(
            String sessionId, Integer limit, String after, String prefix, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        return Blocking.call(() -> toPage(workspace.list(sessionId, prefix, page)))
                .map(ResponseEntity::ok);
    }

    private static byte[] bytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static WorkspaceFilePage toPage(PageResult<WorkspaceFileService.Entry> page) {
        return new WorkspaceFilePage(
                page.data().stream().map(WorkspaceApiController::toDto).toList(), page.hasMore(), page.nextCursor());
    }

    private static WorkspaceFile toDto(WorkspaceFileService.Entry entry) {
        return new WorkspaceFile()
                .path(entry.path())
                .sizeBytes(entry.sizeBytes())
                .updatedAt(ApiTimes.utc(entry.updatedAt()));
    }
}
