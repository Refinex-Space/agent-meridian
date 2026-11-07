/**
 * 集成测试夹具：以单例方式复用的 Testcontainers 容器（PostgreSQL、Redis、MinIO），为 Spring 测试注册连接属性，
 * 为纯 JDBC 测试提供数据源、迁移与清理（测试规范「集成测试夹具」）。
 *
 * <ul>
 *   <li>{@link cn.refinex.meridian.testsupport.MeridianContainers}：容器单例，首次使用时启动，JVM 退出时由
 *       Testcontainers 回收。
 *   <li>{@link cn.refinex.meridian.testsupport.TestDatabase}：两个角色的数据源、Flyway 迁移、SQL 脚本、清空 schema。
 *   <li>{@link cn.refinex.meridian.testsupport.SpringContainerProperties}：{@code @DynamicPropertySource} 用的属性注册。
 * </ul>
 *
 * <p>边界：不进入任何生产构件，只作为其他模块的 test 作用域依赖；不依赖平台模块，persistence 自己的集成测试也用它。
 * 运行需要 Docker（测试规范「集成测试夹具」）。
 */
package cn.refinex.meridian.testsupport;
