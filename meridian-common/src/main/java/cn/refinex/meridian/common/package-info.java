/**
 * 平台通用基础库：带前缀的 ULID、错误码与平台异常、游标分页、时钟、JSON 序列化配置、{@code PlatformContext} 及其传递、阻塞调用的虚拟线程调度。
 *
 * <p>边界：不负责任何业务语义、HTTP 映射、数据访问和智能体执行；problem+json 映射在 {@code meridian-security}，仓储基类在 {@code meridian-persistence}。
 *
 * <p>允许的依赖：不依赖任何平台模块，只依赖 JDK、Jackson 与 reactor-core；reactor-core 只允许出现在 {@code context} 与 {@code concurrent} 包。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.common;
