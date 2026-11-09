/**
 * API Key 的用例：管理（生成、查看、改名、吊销与广播）与内部校验（ADR-0042）。用例自己绑定 {@code PlatformContext} 与事务边界；
 * 明文只出现在创建结果中。
 */
package cn.refinex.meridian.controlplane.apikey.app;
