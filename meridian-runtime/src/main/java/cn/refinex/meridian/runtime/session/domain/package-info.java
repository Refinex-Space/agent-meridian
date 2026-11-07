/**
 * 会话与线程的值对象（状态取值、新建参数与读出的记录），以及轮次执行的规则：崩溃恢复的判定（{@code RecoveryPlanner}）与
 * 停止原因到轮次状态的映射（{@code TurnEnding}）。纯 Java，不依赖 Spring 与 infra。
 */
package cn.refinex.meridian.runtime.session.domain;
