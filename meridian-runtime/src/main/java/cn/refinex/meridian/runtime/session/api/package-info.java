/**
 * 会话与线程的 HTTP 处理器：实现生成的 {@code SessionsApi}、{@code ThreadsApi}，只做 decode → call → map，
 * 业务在 {@code session.app}。作用域检查在调用用例之前（架构设计「授权」）。
 */
package cn.refinex.meridian.runtime.session.api;
