/**
 * outbox 消息的契约样本（ADR-0048）：runtime 写出的用量与审计消息的标准形状。runtime 的契约测试断言自己的消息记录序列化后与样本一致，
 * control-plane 的消费者测试把样本（替换租户等取值后）发布到 Redis Stream，两边按同一份样本对齐。
 */
package cn.refinex.meridian.testsupport.outbox;
