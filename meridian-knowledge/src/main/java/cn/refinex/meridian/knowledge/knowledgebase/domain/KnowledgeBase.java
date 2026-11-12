package cn.refinex.meridian.knowledge.knowledgebase.domain;

import java.time.Instant;

/**
 * 知识库。
 *
 * @param id 知识库 ID（{@code kb_…}）
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param name 名称
 * @param description 说明；可为空
 * @param embeddingModel Embedding 模型 ID（{@code mdl_…}），创建后不可改
 * @param documentCount 文档数（含写入中与失败的）；只在查询时填写，插入时为 0
 * @param createdBy 创建者：API Key ID 或 {@code user:} 加控制台用户的主体标识
 * @param createdAt 创建时间
 * @param updatedAt 最后修改时间
 */
public record KnowledgeBase(
        String id,
        String tenantId,
        String projectId,
        String name,
        String description,
        String embeddingModel,
        long documentCount,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
