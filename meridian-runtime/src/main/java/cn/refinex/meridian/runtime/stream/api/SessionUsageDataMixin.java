package cn.refinex.meridian.runtime.stream.api;

import cn.refinex.meridian.api.model.SessionUsageData;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.jackson.JacksonMixin;

/**
 * 公开 API 输出 {@link SessionUsageData} 时，{@code model_id} 为空就不出现（规范：本轮没有模型调用时不出现，ADR-0050）。其余可空字段在
 * 规范中是 {@code type: [T, 'null']}，照常输出 null；事件存储与 SSE 不经 DTO，由 {@code EngineJson} 省略。
 */
@JacksonMixin(SessionUsageData.class)
abstract class SessionUsageDataMixin {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("model_id")
    abstract String getModelId();
}
