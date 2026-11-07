/**
 * 会话事件流：事件的持久化（{@code rt.session_events}）与 seq 分配；T06 起加入 Redis Stream 推送，T07 加入 SSE。
 *
 * <p>事件写入只有一个入口 {@code stream.app.SessionEventWriter}，Worker、API、回收器都经它写入：同一事务里校验租约、
 * 锁定会话行分配 seq、插入事件，并按状态事件推进会话与线程的状态（架构设计「一轮执行的生命周期」「事件」）。
 *
 * <p>允许依赖 {@code session.domain}（状态取值）与 {@code dispatch.domain}（节点身份）；租约条件以 SQL 直接校验 {@code rt.turns}。
 */
package cn.refinex.meridian.runtime.stream;
