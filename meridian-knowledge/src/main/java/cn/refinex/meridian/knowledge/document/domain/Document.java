package cn.refinex.meridian.knowledge.document.domain;

import java.time.Instant;
import java.util.List;

/**
 * 知识库中的一份文档。
 *
 * @param id 文档 ID（{@code doc_…}）
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param knowledgeBaseId 所属知识库
 * @param filename 规范化后的文件名
 * @param mediaType 服务端检测的媒体类型
 * @param sizeBytes 原文件字节数
 * @param sha256 原文件的 SHA-256
 * @param objectKey 原文件在对象存储中的键
 * @param aclTags 访问标签
 * @param status 状态
 * @param parser 最近一次成功解析所用的解析器；可为空
 * @param chunkCount 当前可检索的切片数
 * @param indexBytes 当前切片的正文与向量字节数
 * @param createdBy 上传者
 * @param createdAt 上传时间
 * @param updatedAt 最后更新时间
 */
public record Document(
        String id,
        String tenantId,
        String projectId,
        String knowledgeBaseId,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String objectKey,
        List<String> aclTags,
        DocumentStatus status,
        String parser,
        int chunkCount,
        long indexBytes,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 复制访问标签。 */
    public Document {
        aclTags = List.copyOf(aclTags);
    }

    /**
     * 原文件在对象存储中的键（ADR-0051）：{@code kb/{tenant}/{project}/{knowledge_base}/{document}}。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param knowledgeBaseId 知识库 ID
     * @param documentId 文档 ID
     * @return 键
     */
    public static String objectKey(String tenantId, String projectId, String knowledgeBaseId, String documentId) {
        return "kb/" + tenantId + "/" + projectId + "/" + knowledgeBaseId + "/" + documentId;
    }
}
