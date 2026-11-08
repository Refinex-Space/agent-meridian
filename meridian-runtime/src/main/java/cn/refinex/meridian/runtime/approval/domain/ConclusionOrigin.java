package cn.refinex.meridian.runtime.approval.domain;

/**
 * 待处理动作的结论从哪里来（架构设计「待处理动作」「消息投递」「权限判定」），写入结论的 {@code origin}，用于审计与给模型的说明。
 */
public enum ConclusionOrigin {
    /** 调用方的回应事件。 */
    CALLER,
    /** 截止时间已过：工具确认视为拒绝、自定义工具以 {@code timeout} 错误回传、恢复确认视为 skip。 */
    TIMEOUT,
    /** 等待回应时收到 {@code user.interrupt}：一律拒绝（恢复确认视为 skip）。 */
    INTERRUPT,
    /** 无人值守会话仍以 {@code PERMISSION_ASKING} 结束：平台自动拒绝。 */
    UNATTENDED
}
