package cn.refinex.meridian.persistence.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个跨租户运行的 {@link SystemRepository} 子类，并写明为什么它可以不带租户条件。
 *
 * <p>架构规则要求每个 {@code SystemRepository} 的子类都带这个注解（{@code meridian-arch-tests} 检查），
 * 理由会在代码评审中逐条核对，见架构设计「租户隔离」与 ADR-0020。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrossTenant {

    /**
     * 跨租户的理由，例如"按 API Key 哈希查找主体，此时还不知道租户"。
     *
     * @return 理由，不能为空
     */
    String reason();
}
