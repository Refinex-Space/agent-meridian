package cn.refinex.meridian.common.context;

/**
 * 会话的值守方式，决定需要人回应的待处理动作如何处理（架构设计「核心概念」「待处理动作」）。
 */
public enum Interaction {
    /** 有人值守（默认）：需要确认的操作产生待处理动作，等待回应。 */
    ATTENDED,
    /** 无人值守：需要确认的操作按权限改写规则处理，不等待人。 */
    UNATTENDED
}
