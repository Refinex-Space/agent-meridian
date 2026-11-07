/**
 * 收件箱用例：{@code InboxPort} 的数据库实现（steer 消息的带租约消费），以及 Worker 使用的合并与重新投递。
 * 方法会阻塞，响应式调用方经 {@code Blocking} 调用；事务边界在这里定义。
 */
package cn.refinex.meridian.runtime.delivery.app;
