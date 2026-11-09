/**
 * 租户的用例：创建、查看、改名、配额与设置、删除，以及配额变更的广播 {@code config.invalidate}（ADR-0042）。用例自己绑定
 * {@code PlatformContext} 与事务边界。
 */
package cn.refinex.meridian.controlplane.tenant.app;
