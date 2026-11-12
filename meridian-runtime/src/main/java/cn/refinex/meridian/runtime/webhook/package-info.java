/**
 * Webhook 上下文（架构设计「Webhook」，ADR-0049）：订阅的四种状态事件与事件同一事务写入 outbox（{@code rt.webhook_events}），
 * 独立的投递器按项目的订阅（control-plane 经内部接口给出）扇出为投递记录，在事务外签名并投递，失败按退避重试，至少一次；公开 API
 * 查询投递记录与手动重投。投递不阻塞轮次。
 */
package cn.refinex.meridian.runtime.webhook;
