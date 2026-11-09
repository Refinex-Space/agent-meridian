/**
 * 成员上下文：租户成员、角色绑定，以及授权判定的角色绑定来源（架构设计「授权」，ADR-0042）。角色矩阵本身在
 * {@code security.rbac}。分 {@code api}、{@code app}、{@code domain}、{@code infra} 四层。
 */
package cn.refinex.meridian.controlplane.member;
