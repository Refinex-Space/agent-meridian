package cn.refinex.meridian.modelclient;

import java.util.List;
import java.util.Objects;

/**
 * 一次重排请求。
 *
 * @param tenantId 调用代表的租户，用量与预算计入它
 * @param projectId 调用代表的项目；可为空，表示租户级的调用
 * @param model 本租户模型目录中的 rerank 模型 ID（{@code mdl_…}）
 * @param query 查询文本，不能为空
 * @param documents 候选文本，1 至 {@value RerankClient#MAX_DOCUMENTS} 条，每条不能为空
 * @param topN 只返回得分最高的前 n 条；可为空，表示返回全部
 */
public record RerankRequest(
        String tenantId, String projectId, String model, String query, List<String> documents, Integer topN) {

    /**
     * 校验并复制候选。
     *
     * @throws IllegalArgumentException 查询为空、候选条数不合法或有空文本时
     */
    public RerankRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(model, "model");
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("重排的查询不能为空");
        }
        documents = List.copyOf(documents);
        if (documents.isEmpty() || documents.size() > RerankClient.MAX_DOCUMENTS) {
            throw new IllegalArgumentException(
                    "重排的候选数必须在 1 至 " + RerankClient.MAX_DOCUMENTS + " 之间：" + documents.size());
        }
        if (documents.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("重排的候选不能有空文本");
        }
    }
}
