package cn.refinex.meridian.security.apikey;

import java.util.Optional;

/** API Key 的作用域，取值见架构设计「授权」。 */
public enum ApiKeyScope {
    /** 创建、更新、删除、归档会话，发送事件。 */
    SESSIONS_WRITE("sessions:write"),
    /** 查询会话、事件、线程、待处理动作，订阅事件流。 */
    SESSIONS_READ("sessions:read"),
    /** 知识库检索。 */
    KNOWLEDGE_READ("knowledge:read"),
    /** 知识库写入。 */
    KNOWLEDGE_WRITE("knowledge:write"),
    /** 上传文件。 */
    FILES_WRITE("files:write"),
    /** 调用模型直通接口。 */
    MODELS_INVOKE("models:invoke");

    private final String value;

    ApiKeyScope(String value) {
        this.value = value;
    }

    /**
     * 返回作用域的取值。
     *
     * @return 例如 {@code sessions:write}
     */
    public String value() {
        return value;
    }

    /**
     * 按取值查找作用域。
     *
     * @param value 作用域取值
     * @return 作用域；取值未知时为空
     */
    public static Optional<ApiKeyScope> fromValue(String value) {
        for (ApiKeyScope scope : values()) {
            if (scope.value.equals(value)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
