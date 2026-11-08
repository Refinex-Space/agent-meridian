package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import java.util.List;
import java.util.Objects;

/**
 * 一轮开始前合成权限的输入：平台安全规则以外的四层规则与会话的值守方式（架构设计「权限判定」）。平台安全规则固定，由
 * {@link PermissionSynthesizer} 自己加入。
 *
 * <p>租户、项目、会话三层只有规则：模式取自智能体配置，会话只能经值守方式收紧（{@code unattended} 固定为 {@code DONT_ASK}），
 * 其他层不参与模式的决定。
 *
 * @param tenant 租户策略的规则；可为空列表
 * @param project 项目策略的规则，含"记住此决定"写入的项目级规则；可为空列表
 * @param agentVersion 智能体版本自带的策略（模式加规则）；必填
 * @param session 会话级规则，即"记住此决定"写入的会话级规则；可为空列表
 * @param interaction 会话的值守方式；必填
 */
public record PermissionLayers(
        List<PermissionRule> tenant,
        List<PermissionRule> project,
        PermissionPolicy agentVersion,
        List<PermissionRule> session,
        Interaction interaction) {

    /**
     * 创建合成输入，对列表做不可变复制。
     *
     * @throws NullPointerException 缺少智能体策略或值守方式时
     */
    public PermissionLayers {
        Objects.requireNonNull(agentVersion, "agentVersion");
        Objects.requireNonNull(interaction, "interaction");
        tenant = tenant == null ? List.of() : List.copyOf(tenant);
        project = project == null ? List.of() : List.copyOf(project);
        session = session == null ? List.of() : List.copyOf(session);
    }
}
