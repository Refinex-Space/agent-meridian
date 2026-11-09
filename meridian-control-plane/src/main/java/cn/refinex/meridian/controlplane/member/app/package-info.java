/**
 * 成员与角色绑定的用例，以及授权判定所需的角色绑定来源 {@code DatabaseGrantSource}（ADR-0042）。用例自己绑定
 * {@code PlatformContext} 与事务边界；角色判断只经 {@code security.rbac} 的矩阵。
 */
package cn.refinex.meridian.controlplane.member.app;
