package cn.refinex.meridian.engine.spi.spec;

/**
 * 工具调用的副作用等级，决定崩溃恢复时能否重放，见架构设计「平台工具」「崩溃恢复」。
 */
public enum SideEffect {
    /** 无副作用，可以重放。 */
    NONE("none"),
    /** 重复执行结果相同，可以重放。 */
    IDEMPOTENT("idempotent"),
    /** 有副作用，不能重放，必须带幂等键；状态未知时产生 {@code recovery_confirmation} 待处理动作。 */
    NON_IDEMPOTENT("non_idempotent");

    private final String value;

    SideEffect(String value) {
        this.value = value;
    }

    /**
     * 返回配置、账本与 API 中使用的取值。
     *
     * @return 小写下划线形式的取值
     */
    public String value() {
        return value;
    }
}
