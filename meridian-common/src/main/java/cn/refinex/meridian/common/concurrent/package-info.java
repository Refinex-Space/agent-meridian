/**
 * 阻塞调用的虚拟线程调度：{@link cn.refinex.meridian.common.concurrent.Blocking}。
 *
 * <p>runtime（WebFlux）中所有阻塞调用都经它离开 Reactor 线程，并在虚拟线程上恢复 {@code PlatformContext}；
 * MVC 服务本身运行在虚拟线程上，不需要它。本包可以依赖 reactor-core（架构设计「模块依赖规则」）。
 */
package cn.refinex.meridian.common.concurrent;
