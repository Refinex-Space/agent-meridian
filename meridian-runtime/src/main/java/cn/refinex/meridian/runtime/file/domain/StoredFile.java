package cn.refinex.meridian.runtime.file.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个上传的文件（{@code rt.files}）。
 *
 * @param id 文件 ID（{@code fil_…}）；必填
 * @param tenantId 租户 ID；必填
 * @param projectId 项目 ID；必填
 * @param filename 规范化后的文件名；必填
 * @param mediaType 服务端检测的媒体类型；必填
 * @param sizeBytes 字节数，大于 0
 * @param sha256 内容的 SHA-256（小写十六进制）；必填
 * @param objectKey 对象存储的键；必填
 * @param createdBy 上传者；必填
 * @param createdAt 上传时间；必填
 */
public record StoredFile(
        String id,
        String tenantId,
        String projectId,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String objectKey,
        String createdBy,
        Instant createdAt) {

    /**
     * 创建文件记录。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public StoredFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 文件内容在对象存储中的键：{@code rt/{tenant}/{project}/files/{file_id}}。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param fileId 文件 ID
     * @return 键
     */
    public static String objectKey(String tenantId, String projectId, String fileId) {
        return "rt/" + tenantId + "/" + projectId + "/files/" + fileId;
    }

    /**
     * 是否为图片（按检测的媒体类型）。
     *
     * @return {@code image/*} 时为 true
     */
    public boolean isImage() {
        return mediaType.startsWith("image/");
    }
}
