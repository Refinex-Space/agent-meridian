package cn.refinex.meridian.engine.spi.port;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code none} 档工作区中文件的约定：命名空间、键与值的格式，内核（{@code RemoteFilesystem} 经 {@code MeridianBaseStore}）与 runtime
 * （放置上传文件、工作区 API）共用，见架构设计「文件系统装配」（ADR-0034、ADR-0050）。
 *
 * <ul>
 *   <li>命名空间 {@code [tenant_id, project_id, end_user_id, session_id]}，没有终端用户时第三段为 {@value #NO_END_USER}。
 *   <li>键是 {@code /} 加相对工作区根的路径。
 *   <li>值与 AgentScope 2.0.4 {@code RemoteFilesystem} 的 {@code FileData} 相同：{@code content}、{@code encoding}（合法 UTF-8 为
 *       {@code utf-8}，否则 {@code base64}）、{@code created_at}、{@code modified_at}；读取时 {@code content} 也可能是行的数组。
 *       {@code engine-agentscope-test} 的测试守住两边互通。
 * </ul>
 *
 * <p>纯函数，线程安全。
 */
public final class WorkspaceFiles {

    /** 没有终端用户时命名空间第三段的占位，与「身份映射」中 {@code userId} 的约定一致。 */
    public static final String NO_END_USER = "_";

    /** 上传文件在工作区中的目录，相对工作区根（ADR-0050）。 */
    public static final String UPLOADS_DIR = "uploads";

    /** 文本内容的编码标记。 */
    static final String UTF_8 = "utf-8";

    /** 二进制内容的编码标记。 */
    static final String BASE64 = "base64";

    private WorkspaceFiles() {}

    /**
     * 会话工作区的命名空间。
     *
     * @param tenantId 租户 ID；必填
     * @param projectId 项目 ID；必填
     * @param endUserId 终端用户 ID；为空或空白时用 {@value #NO_END_USER}
     * @param sessionId 会话 ID；必填
     * @return 命名空间
     */
    public static List<String> namespace(String tenantId, String projectId, String endUserId, String sessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        String endUser = endUserId == null || endUserId.isBlank() ? NO_END_USER : endUserId;
        return List.of(tenantId, projectId, endUser, sessionId);
    }

    /**
     * 相对路径对应的存储键。
     *
     * @param relativePath 相对工作区根的路径，不以 {@code /} 开头
     * @return 键
     */
    public static String key(String relativePath) {
        return "/" + relativePath;
    }

    /**
     * 存储键对应的相对路径。
     *
     * @param key 存储键
     * @return 相对工作区根的路径
     */
    public static String relativePath(String key) {
        String path = key;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /**
     * 上传文件在工作区中的路径：{@code uploads/{file_id}/{filename}}。
     *
     * @param fileId 文件 ID（{@code fil_…}）
     * @param filename 规范化后的文件名（不含 {@code /}）
     * @return 相对工作区根的路径
     */
    public static String uploadPath(String fileId, String filename) {
        return UPLOADS_DIR + "/" + fileId + "/" + filename;
    }

    /**
     * 把文件内容编码为存储的值。
     *
     * @param content 文件内容
     * @param now 写入时间，同时作为创建与修改时间
     * @return 值
     */
    public static Map<String, Object> encode(byte[] content, Instant now) {
        Map<String, Object> value = new LinkedHashMap<>();
        Optional<String> text = utf8(content);
        value.put("content", text.orElseGet(() -> Base64.getEncoder().encodeToString(content)));
        value.put("encoding", text.isPresent() ? UTF_8 : BASE64);
        value.put("created_at", now.toString());
        value.put("modified_at", now.toString());
        return value;
    }

    /**
     * 从存储的值解码出文件内容。
     *
     * @param value 值
     * @return 内容；值不是文件（没有 {@code content}）时为空
     */
    public static Optional<byte[]> decode(Map<String, Object> value) {
        Object content = value.get("content");
        String text;
        if (content instanceof String s) {
            text = s;
        } else if (content instanceof List<?> lines) {
            text = String.join("\n", lines.stream().map(String::valueOf).toList());
        } else {
            return Optional.empty();
        }
        if (BASE64.equals(value.get("encoding"))) {
            return Optional.of(Base64.getMimeDecoder().decode(text));
        }
        return Optional.of(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 值中文件内容的字节数，不完整解码 Base64。
     *
     * @param value 值
     * @return 字节数；值不是文件时为空
     */
    public static Optional<Long> contentSize(Map<String, Object> value) {
        if (value.get("content") instanceof String text && BASE64.equals(value.get("encoding"))) {
            return Optional.of((long) Base64.getMimeDecoder().decode(text).length);
        }
        return decode(value).map(bytes -> (long) bytes.length);
    }

    private static Optional<String> utf8(byte[] content) {
        try {
            return Optional.of(StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
