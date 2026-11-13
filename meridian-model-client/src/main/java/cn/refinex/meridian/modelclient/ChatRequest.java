package cn.refinex.meridian.modelclient;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 一次非流式对话补全请求。
 *
 * @param tenantId 调用代表的租户，用量与预算计入它
 * @param projectId 调用代表的项目；可为空，表示租户级的调用
 * @param model 本租户模型目录中的对话模型 ID（{@code mdl_…}）
 * @param messages 消息，1 至 32 条
 * @param maxTokens 输出 token 上限；可为空，表示用模型的默认值
 * @param temperature 采样温度；可为空
 */
public record ChatRequest(
        String tenantId,
        String projectId,
        String model,
        List<Message> messages,
        Integer maxTokens,
        Double temperature) {

    /** 一次请求的最多消息数，与内部接口的 {@code maxItems} 相同。 */
    public static final int MAX_MESSAGES = 32;

    /**
     * 校验并复制消息。
     *
     * @throws IllegalArgumentException 消息条数不合法时
     */
    public ChatRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(model, "model");
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("消息条数必须在 1 至 " + MAX_MESSAGES + " 之间：" + messages.size());
        }
    }

    /**
     * 一条文本消息。
     *
     * @param role 角色
     * @param content 文本，不能为空
     */
    public record Message(Role role, String content) {

        /**
         * 校验。
         *
         * @throws IllegalArgumentException 文本为空时
         */
        public Message {
            Objects.requireNonNull(role, "role");
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("消息文本不能为空");
            }
        }
    }

    /** 消息角色。 */
    public enum Role {
        /** 系统提示词。 */
        SYSTEM,
        /** 用户。 */
        USER,
        /** 助手。 */
        ASSISTANT;

        /**
         * 接口中的取值。
         *
         * @return 小写的常量名
         */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
