package cn.refinex.meridian.security.rbac;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明管理 API 处理器方法所需的操作类别。{@link PermissionInterceptor} 在调用处理器之前判定；{@code /admin/} 下没有本注解的处理器
 * 一律拒绝（失败即关闭）。租户与项目取自路径变量 {@code tenant_id}、{@code project_id}。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * 所需的操作类别。
     *
     * @return 操作类别
     */
    Permission value();
}
