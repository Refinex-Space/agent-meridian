/**
 * 租户上下文：租户、租户配额与租户设置（架构设计「租户层级」，ADR-0042）。删除租户经进程内事件级联到项目与 API Key。
 *
 * <p>分 {@code api}、{@code app}、{@code domain}、{@code infra} 四层；跨租户的租户目录在 {@code infra.system}。
 */
package cn.refinex.meridian.controlplane.tenant;
