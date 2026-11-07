/**
 * 端口的内存实现：{@code meridian-engine-spi} 中内核需要的 runtime 端口，供场景测试在不依赖数据库的情况下验证
 * 内核行为（T03、T04 起）。语义与架构设计「端口」表一致，租约条件用内存中的"活动轮次"模拟。
 *
 * <p>只用于测试，不进入生产构件；数据库与对象存储的实现由 T05、T06 提供。
 */
package cn.refinex.meridian.engine.agentscope.testkit.port;
