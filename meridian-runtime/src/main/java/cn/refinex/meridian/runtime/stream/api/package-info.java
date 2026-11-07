/**
 * 事件的 HTTP 处理器：实现生成的 {@code EventsApi}（发送事件、列出事件、会话流与线程流），只做 decode → call → map。
 * 发送事件的业务在 {@code delivery.app.EventIntake}，事件流在 {@code stream.app.EventStreamService}。
 */
package cn.refinex.meridian.runtime.stream.api;
