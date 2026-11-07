/**
 * 集成测试夹具：以单例方式复用的 Testcontainers 容器（PostgreSQL、Redis、MinIO）。
 *
 * <p>边界：不进入任何生产构件，只作为其他模块的 test 作用域依赖。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}、{@code meridian-persistence}。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.testsupport;
