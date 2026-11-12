package cn.refinex.meridian.modelclient;

import java.util.List;
import java.util.Objects;

/**
 * 一批 Embedding 请求。
 *
 * @param tenantId 调用代表的租户，用量与预算计入它
 * @param projectId 调用代表的项目；可为空，表示租户级的调用
 * @param model 本租户模型目录中的 Embedding 模型 ID（{@code mdl_…}）
 * @param inputs 文本，1 至 {@value EmbeddingClient#MAX_BATCH} 条，每条不能为空
 * @param dimensions 输出维度；可为空，表示模型的默认维度
 */
public record EmbeddingRequest(
        String tenantId, String projectId, String model, List<String> inputs, Integer dimensions) {

    /**
     * 校验并复制输入。
     *
     * @throws IllegalArgumentException 条数为 0、超过上限或有空文本时
     */
    public EmbeddingRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(model, "model");
        inputs = List.copyOf(inputs);
        if (inputs.isEmpty() || inputs.size() > EmbeddingClient.MAX_BATCH) {
            throw new IllegalArgumentException(
                    "一批 Embedding 的条数必须在 1 至 " + EmbeddingClient.MAX_BATCH + " 之间：" + inputs.size());
        }
        if (inputs.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Embedding 的输入不能有空文本");
        }
    }
}
