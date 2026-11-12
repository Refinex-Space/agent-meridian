/**
 * Webhook（架构设计「Webhook」，ADR-0049）：项目级的推送配置，地址、订阅的事件与签名密钥。签名密钥由平台生成，只在创建响应中出现一次，
 * 以租户数据密钥加密保存；runtime 经内部接口读取并投递，投递记录与重投在 runtime 的公开 API。
 */
package cn.refinex.meridian.controlplane.webhook;
