package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import java.time.Duration;
import java.util.Objects;

/**
 * 提供系统提示词的租户层（架构设计「上下文管理」中的提示词分层，ADR-0035）。装配器在缓存未命中、装配实例时调用一次，结果随实例缓存。
 *
 * <p>装配在 {@code Blocking} 的虚拟线程上执行，{@code PlatformContext} 经 {@link PlatformContexts#find()} 取得；智能体版本属于一个
 * 项目，所以同一缓存键下的会话都属于同一租户，第一次装配时读到的租户层对它们都成立。
 */
@FunctionalInterface
public interface TenantPromptProvider {

    /** 读取租户提示词的最长等待，与读取技能内容相同。 */
    Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 返回租户层提示词。
     *
     * @param profile 装配对象
     * @return 租户提示词；没有时为空字符串
     */
    String tenantPrompt(AgentProfile profile);

    /**
     * 没有租户层的提供者，测试用。
     *
     * @return 提供者
     */
    static TenantPromptProvider none() {
        return profile -> "";
    }

    /**
     * 经 {@link AgentConfigPort#tenantPrompt} 读取当前租户的提示词。装配时必须有 {@code PlatformContext}：没有时无法确定租户，缓存下来的
     * 实例会永久缺少租户层，所以直接失败。
     *
     * @param configs 配置端口
     * @return 提供者
     */
    static TenantPromptProvider fromPort(AgentConfigPort configs) {
        Objects.requireNonNull(configs, "configs");
        return profile -> {
            PlatformContext platform = PlatformContexts.find()
                    .orElseThrow(() -> new IllegalStateException("装配智能体实例时没有 PlatformContext，无法读取租户提示词："
                            + profile.key().agentVersionId()));
            String prompt = configs.tenantPrompt(platform.tenantId()).block(READ_TIMEOUT);
            return prompt == null ? "" : prompt;
        };
    }
}
