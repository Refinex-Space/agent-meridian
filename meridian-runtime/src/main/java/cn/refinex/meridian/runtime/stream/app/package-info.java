/**
 * 事件写入用例：{@code SessionEventWriter} 定义事务边界，是 Worker、API 与回收器共用的唯一写入入口；
 * {@code DatabaseEventSink} 是 {@code EventSinkPort} 的实现，供内核直接写入子线程事件与自定义事件。
 */
package cn.refinex.meridian.runtime.stream.app;
