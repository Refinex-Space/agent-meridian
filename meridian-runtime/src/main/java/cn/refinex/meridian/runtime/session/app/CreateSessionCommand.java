package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.runtime.session.domain.EndUserAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 创建会话的请求（{@code POST /v1/sessions}），已由 API 层完成格式校验。
 *
 * @param agent 智能体 ID（{@code agt_…}，绑定当前版本）或智能体版本 ID（{@code agv_…}）；必填
 * @param environmentId 环境 ID（{@code env_…}），绑定当前修订；必填
 * @param endUserId 终端用户标识；可为空
 * @param endUserAttributes 终端用户属性；可为空
 * @param interaction 值守方式；必填
 * @param metadata 元数据；可为空
 * @param customTools 自定义工具声明；可为空
 */
public record CreateSessionCommand(
        String agent,
        String environmentId,
        String endUserId,
        EndUserAttributes endUserAttributes,
        Interaction interaction,
        Map<String, String> metadata,
        List<CustomToolDeclaration> customTools) {

    /**
     * 创建请求。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public CreateSessionCommand {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(interaction, "interaction");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        customTools = customTools == null ? List.of() : List.copyOf(customTools);
    }
}
