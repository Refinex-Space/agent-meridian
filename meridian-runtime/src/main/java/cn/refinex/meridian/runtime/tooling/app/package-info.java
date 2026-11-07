/**
 * 工具账本用例：{@code ToolLedgerPort} 的数据库实现（带租约的开始与结束记录），以及恢复时按轮次读取账本。
 * 方法会阻塞或经 {@code Blocking} 调度；事务边界在这里定义。
 */
package cn.refinex.meridian.runtime.tooling.app;
