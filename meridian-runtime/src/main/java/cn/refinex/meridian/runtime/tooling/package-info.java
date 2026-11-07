/**
 * 工具上下文：工具调用账本 {@code rt.tool_invocations}（{@code ToolLedgerPort} 的实现），崩溃恢复据它生成恢复说明
 * （架构设计「端口」「崩溃恢复」）。平台工具本身由 T10a 起实现；T19 的知识检索端口、T10b 的联网端口也放在这里。
 */
package cn.refinex.meridian.runtime.tooling;
