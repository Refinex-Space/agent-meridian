/**
 * 控制台用户的声明式授权（架构设计「授权」，ADR-0042）：角色 {@link cn.refinex.meridian.security.rbac.Role}、操作类别
 * {@link cn.refinex.meridian.security.rbac.Permission}、角色矩阵 {@link cn.refinex.meridian.security.rbac.RoleMatrix}，处理器方法上的
 * {@link cn.refinex.meridian.security.rbac.RequiresPermission} 由 MVC 拦截器判定。
 *
 * <p>角色绑定的存储不在本包：control-plane 实现 {@link cn.refinex.meridian.security.rbac.GrantSource}。T14b 接 OIDC 后只替换身份来源，
 * 矩阵与判定不变。允许的依赖：{@code meridian-common}、本模块的 {@code oidc} 包、Spring Web MVC；不访问数据库。
 */
package cn.refinex.meridian.security.rbac;
