/**
 * 端口：内核（{@code meridian-engine-agentscope} 的平台工具、中间件、状态存储）需要的 runtime 能力，
 * 由 runtime 实现并在 {@code config} 包注入，见架构设计「端口」表。
 *
 * <p>约定：
 * <ul>
 *   <li>签名只出现平台类型，禁止出现 AgentScope 与 Spring 类型。
 *   <li>被 AgentScope 同步接口调用的端口（状态存储、工作区存储、租约查询）是同步方法；实现中的 JDBC 调用由
 *       runtime 负责调度，不在 Reactor 线程上阻塞（编码规范「并发与响应式」）。其余端口返回 {@code Mono}/{@code Flux}。
 *   <li>租户、项目、会话、轮次与租约 epoch 从调用方的 {@code PlatformContext} 取：响应式方法从 Reactor Context，
 *       同步方法由调用方显式传入；写入都带租约条件，失去租约时以 {@code LeaseLostException} 失败。
 * </ul>
 *
 * <p>为落实架构设计已规定的行为可以新增端口或给既有端口加方法，并同步更新「端口」表；删除端口或改变既有
 * 端口的语义要先提 ADR。
 */
package cn.refinex.meridian.engine.spi.port;
